# План: сверка с перевозчиком

Основание: спека `docs/specs/reconciliation.md` (второй опрос Q1–Q15, 17.11.2026). Ветка `reconciliation` от
`operator-problems` (или от `main`, если (a) к началу работы влита). Работа по TDD: в каждом шаге сначала пишутся
тесты шага и запускаются, чтобы увидеть, что они падают **по нужной причине** (красные), затем код до зелёного, затем
рефакторинг. Рабочего кода без падающего теста нет. Один шаг — один коммит; после каждого шага — гейт шага
(`./gradlew :dispatch:test` или указанный в шаге), в конце — полный гейт.

## 0. Исходное состояние

- Миграции V1–V10. Таблицы `reconciliation` нет; в `dispatch_operation` нет `reconciled_at`/`next_reconcile_at`, в
  `problem` нет `reconciliation_id`. `acknowledged_via` уже допускает `RECONCILIATION` (V2), `inbox.source` —
  `RECONCILIATION` (V3); у записей не из ленты `feed_seq` пуст (`inbox_feed_has_feed_seq` требует его только у `FEED`).
- `inbox_message_id_unique (message_id)` (V3). На него опираются `InboxDao.countDuplicate(messageId)` (`WHERE
  message_id = …`) и `insertReceived` (`ON CONFLICT (message_id) WHERE message_id IS NOT NULL`). Смена индекса
  ломает этот `ON CONFLICT` — поэтому индекс и код дедупликации меняются в одном шаге.
- `ResultInbox.receive(raw, source)` — `MANDATORY`, пишет `RECEIVED` с `next_attempt_at = now`; дубль → `Optional.empty()`.
- `InboxProcessor`: только `processDue()` → `processNext()` берёт старейшую запись (`claimNextDue`, `SKIP LOCKED`);
  точка сохранения — `TransactionTemplate` с `PROPAGATION_NESTED`. Записи по `id` обработать нельзя. Проблемы —
  `problems.onRecordOutcome` и `onDeliveryStatus(ACKNOWLEDGED)` только если `applyToOperation` вернул `true`.
- `OperatorResults.applyChosen(inboxId, now)` — `applyToRequest` + `makeApplied` (указатель и `GREATEST(seq)`).
  **Статус самой записи `inbox` он не меняет**: для записи ленты (`CONFLICT`) так и задумано в (a), а новая запись
  сверки осталась бы `RECEIVED` и потом была бы разобрана фоном как `STALE`. Для сверки нужен явный
  `recordOutcome(APPLIED, …)` (шаг 12).
- `ProblemRules.decisions(kind, status, disputedRecordIsFinal)` и `requestActions(DeliveryStatus)`; вызываются из
  `ProblemQueryDao` (стр. 135) и `RequestQueryService.getCard` (стр. 55). `ProblemQueryDao` заполняет `message` только
  при `inbox_id`, `currentResult` — только для `RESULT_CONFLICT`.
- `ProblemDao`: `open`, `closeBySystem(requestId, kinds, now)`, `closeByOperator(requestId, kind, …)`, `closeById`,
  `recordOf`.
- `SendQueueDao.retryByOperator` — `WHERE delivery_status IN ('FAILED','UNKNOWN')`.
- `CarrierClient`: `submit`, `results`. `HttpCarrierClientTest` гоняет клиент против `com.sun.net.httpserver.HttpServer`.
  `FakeCarrier`: `answer`, `publish`, `feedUnavailable`, `duringSubmit`, `reset`.
- Фоновые работники — `@Scheduled(fixedDelayString = …)` с проверкой `properties.enabled()` внутри (`SendWorker`).
- `ExchangeProperties` — record с `@DefaultValue`.
- `ContractCheckTests` проверяет только «вызванный путь есть в контракте»; путь в контракте без реализации сборку не
  ломает — контракт можно править раньше кода.
- `TestRows.deleteRequestData`: `problem` → `idempotency_key` → обнуление `applied_inbox_id` → `inbox` →
  `exchange_attempt` → `dispatch_operation` → `delivery_request`. С `problem → reconciliation → inbox /
  dispatch_operation` строки `reconciliation` удаляются после `problem` и до `inbox`.
- `SchemaMigrationTests.allMigrationsAreApplied` — список до `10`.

## Шаг 1. Документы дизайна

**Что делаем**
- `docs/dispatch-design.md`:
  - §4 — таблица `reconciliation`, колонки `dispatch_operation.reconciled_at`/`next_reconcile_at`,
    `problem.reconciliation_id`, уникальность `inbox (source, message_id)`;
  - §5 — дедупликация по `(source, message_id)`, повтор между источниками — `STALE` по правилам;
  - §6 — сверка по спеке: запуски, состав фона, растущая пауза, исходы, эффекты, транзакции, гонки;
  - §7 — виды `EXTERNAL_MISSING`, `RECONCILIATION_CONFLICT`, закрытие системой, единый набор пяти действий;
- спека: статус «согласовано», ссылка на этот план.

**Тесты:** нет. **Готово, когда:** дизайн и спека не противоречат друг другу.

## Шаг 2. Схема V11 и дедупликация `inbox` по паре

**Тесты (сначала, красные)**

Интеграционные, `SchemaMigrationTests` (красные: таблицы и колонок нет). Помощник `insertReconciliation(...)`:

| Тест | Ожидание |
|---|---|
| `allMigrationsAreApplied` (меняется) | список с `11` |
| `reconciliationRunTypeMustBeKnown`, `…OutcomeMustBeKnown`, `…CarrierStateMustBeKnown`, `…CarrierResultStatusMustBeKnown`, `…DeliveryStatusMustBeKnown` | отказ, имя `reconciliation_run_type_valid` / `_outcome_valid` / `_carrier_state_valid` / `_carrier_result_status_valid` / `_delivery_status_valid` |
| `manualRunHasAuthor`, `backgroundRunHasNoAuthor` | `reconciliation_manual_has_author` |
| `errorHasNoCarrierState`, `answerHasCarrierState` | `reconciliation_error_without_state` |
| `externalMissingMeansNotFound` | `reconciliation_missing_is_not_found` |
| `conflictNeedsCarrierAnswer`; `conflictWithoutCarrierResultIsAccepted` | `reconciliation_conflict_has_answer`; `ACCEPTED` без итога принят |
| `externalAheadNeedsResultAndRecord` | `reconciliation_ahead_has_result` |
| `carrierResultOnlyWhenExecuted`, `succeededNeedsTrackingAndPrice`, `rejectedNeedsReason` | `reconciliation_result_needs_executed`, `reconciliation_result_complete` |
| `acknowledgedNeedsCarrierAnswer` | `reconciliation_acknowledged_has_answer` |
| `detailsAreRequired` | `NOT NULL` |
| `localResultIsOptional` | строка без нашего итога (`local_result` пуст) принята |
| `reconciliationNeedsKnownOperation` | FK `reconciliation_operation_fk` |
| `reconcileTimesTogether`, `nextReconcileNotBeforeLast` | `dispatch_operation_reconcile_times_together`, `dispatch_operation_reconcile_order` |
| `problemKindAcceptsReconciliationKinds` | `EXTERNAL_MISSING`, `RECONCILIATION_CONFLICT` приняты `problem_kind_valid` |
| `externalMissingNeedsRequestOnly`, `reconciliationConflictNeedsReconciliation`, `oldKindsHaveNoReconciliation` | `problem_source_matches_kind` |
| `decisionMustFitKind` (дополняется: `RETRIED`/`KEEP_OURS`/`SYSTEM` у `EXTERNAL_MISSING` приняты, `APPLY_CARRIER` у `EXTERNAL_MISSING` — отказ; `APPLY_CARRIER`/`KEEP_OURS`/`SYSTEM` у `RECONCILIATION_CONFLICT` приняты, `RETRIED`/`ACKNOWLEDGED` — отказ) | `problem_decision_matches_kind` |
| `secondOpenReconciliationProblemOfAKindIsRejected` | `problem_open_per_request_kind` действует на новые виды без изменения индекса |
| `sameMessageIdFromTwoSourcesIsAccepted`, `sameMessageIdFromOneSourceIsRejected` | `inbox_source_message_id_unique` |
| `reconciliationIndexesExist` | `reconciliation_request_idx`, `dispatch_operation_next_reconcile_idx` в `pg_indexes` |

Интеграционные, `ResultInboxTests` (дополнение; красный — первый):

| Тест | Ожидание |
|---|---|
| `sameMessageFromReconciliationAfterFeedIsStored` | запись `FEED` и запись `RECONCILIATION` с одним `messageId` — две строки, `duplicate_count` у записи ленты не вырос |
| `repeatedMessageIsCountedWithoutEffect` (есть, должен остаться зелёным) | повтор из ленты — `duplicate_count + 1` |

Каждое ограничение — в своём тесте (несколько нарушений в одной транзакции дают `25P02`).

**Код**
- `V11__reconciliation.sql`: таблица `reconciliation` с FK и ограничениями (каждое «вслепую», как в V9: `outcome
  <> 'X' OR …`); колонки `dispatch_operation`; `problem.reconciliation_id` + пересоздание `problem_kind_valid`,
  `problem_source_matches_kind`, `problem_decision_matches_kind` (`DROP CONSTRAINT` + `ADD CONSTRAINT` в одной
  миграции); замена `inbox_message_id_unique` на `inbox_source_message_id_unique`; индексы.
- `InboxDao.countDuplicate(source, messageId, now)` и `insertReceived` — `ON CONFLICT (source, message_id) WHERE
  message_id IS NOT NULL`; `ResultInbox.receive` передаёт `source`. Новый `InboxDao.findId(source, messageId)` (нужен
  в шагах 6 и 12).
- Java-перечисления: `ProblemKind` + `EXTERNAL_MISSING`, `RECONCILIATION_CONFLICT`. `switch` по `ProblemKind` в
  `ProblemRules.decisions` получает ветки, возвращающие `List.of()` (настоящие правила — шаг 9); `ProblemRulesTest`
  остаётся зелёным.
- `TestRows.deleteRequestData`: `DELETE FROM reconciliation` после `problem`.

**Гейт:** `./gradlew :dispatch:test`; локальный стенд — `make up` поверх существующей базы применяет V11 (проверка
`flyway_schema_history`).

## Шаг 3. `lookup` у клиента перевозчика

**Тесты (сначала, красные: метода нет)**

Модульные, `HttpCarrierClientTest` (дополнение, тот же `HttpServer`):

| Тест | Ожидание |
|---|---|
| `lookupReturnsAcceptedOperation` | `200` `ACCEPTED`, `result: null` → `Found(ACCEPTED, null, raw)` |
| `lookupReturnsExecutedOperationWithResult` | `200` `EXECUTED` с итогом → `Found`, `result` разобран, `raw` дословный |
| `lookupNotFoundKeepsCarrierCodeAndMessage` | `404` `{code, message}` → `NotFound("OPERATION_NOT_FOUND", "…", raw)` |
| `lookupNotFoundWithBrokenBody` | `404` с телом не по схеме → `NotFound(null, null, raw)` |
| `lookupUnavailableThrows` | `503`, `500`, обрыв соединения, таймаут чтения → `CarrierUnavailableException` |
| `lookupForeignOperationIsInvalid` | `200` с другим `operationId` → `Invalid`, причина называет оба id |
| `lookupResultOfOtherOperationIsInvalid` | `result.operationId` не совпадает с запрошенным → `Invalid` |
| `lookupBrokenResultIsInvalid` | `200` с `result` без `price` у `SUCCEEDED` → `Invalid`, причина из `ResultMessage` |
| `lookupNotJsonIsInvalid`, `lookupUnexpectedStatusIsInvalid` | не JSON / `400` с `{code, message}` → `Invalid`, код и текст сохранены |
| `lookupInProgressResultMeansNoResult` | `result` со статусом `IN_PROGRESS` → `Found` с `result = null` |

**Код**
- `carrier/LookupResult` (sealed: `Found`, `NotFound`, `Invalid`), `CarrierClient.lookup(UUID)`,
  `HttpCarrierClient.lookup` (те же таймауты; тело читается строкой, разбирается нашим кодом — чтобы `raw` был
  дословным); проверка `OperationState` — по `carrier-api.yaml`.
- `FakeCarrier.lookup`: программируемый ответ на операцию (`lookupAnswer(operationId, LookupResult)` или по умолчанию
  `NotFound`), `lookupUnavailable(boolean)`, `duringLookup(Runnable)` (для гонки с лентой), счётчик вызовов; `reset`
  очищает.

**Гейт:** `./gradlew :dispatch:test`.

## Шаг 4. Правила сверки (чистые)

**Тесты (сначала, красные: класса нет)**

Модульные, `ReconciliationRulesTest`:

| Тест | Ожидание |
|---|---|
| по строке на каждую строку таблицы §2.4 (параметризованный) | исход |
| `samePriceInDifferentFormsMatches` | `612.5` и `612.50` → `MATCH` |
| `differentMessageIdAndOccurredAtStillMatch` | `MATCH` |
| `rejectedWithDifferentReasonIsConflict` | `CONFLICT` |
| `acknowledgesWheneverCarrierKnows` | признак «перевозчик знает» у `Found`, нет у `NotFound`/`Invalid`/недоступен |
| `firstPauseIsBase`, `sameAnswerDoublesPause`, `pauseStopsAtMax`, `changedAnswerResetsPause`, `unavailableKeepsBasePause` | расчёт `next_reconcile_at` |
| `sameAnswerComparesOutcomeStatesAndDelivery` | изменение любого из исход / `carrier_state` / `carrier_result_status` / наш статус доставки — «ответ изменился» |
| `detailsCombineReasonCarrierTextAndExcerpt` | `details`: причина, `code: message`, начало ответа ≤ 500 символов через `SafeText` |

**Код:** `exchange/reconcile/ReconciliationRules` (`decide(Local, Carrier) → Decision(outcome, carrierKnows)`,
`nextCheck(previous, current, now, pause, max)`, `details(...)`), `ReconciliationOutcome`, `RunType`. Наш итог —
канонизация из полей `delivery_request` в формат `ResultMessage.content()` (общий помощник, чтобы обе стороны
канонизировались одним кодом).

**Гейт:** `./gradlew :dispatch:test --tests '*ReconciliationRulesTest'`, затем весь `:dispatch:test`.

## Шаг 5. Одна проверка: подтверждение, `EXTERNAL_MISSING`, `ERROR`

**Тесты (сначала, красные)**

Интеграционные, `ReconcileDeliveryTests` (вызов `Reconciler.check(requestId, SCHEDULED, null)` — метод одной проверки,
ещё без фона):

| Тест | Ожидание |
|---|---|
| `unknownAcceptedIsAcknowledged` | `ACKNOWLEDGED` via `RECONCILIATION`, `DELIVERY_UNKNOWN` закрыта `SYSTEM`, строка `MATCH`, `acknowledged = true`, `next_attempt_at` не влияет — следующий `SendWorker.runOnce()` ничего не отправляет |
| `failedAcceptedIsAcknowledged` | `DELIVERY_FAILED` закрыта `SYSTEM` |
| `unknownNotFoundIsSentNow` | `EXTERNAL_MISSING`, `next_attempt_at = now`, `attempts`/`retry_from` прежние; `SendWorker.runOnce()` отправляет тот же `operationId` |
| `failedNotFoundChangesNothing` | строка `EXTERNAL_MISSING`, состояние и проблемы без изменений |
| `acknowledgedNotFoundOpensExternalMissing` | проблема `EXTERNAL_MISSING`; повтор проверки — вторая строка, проблема одна |
| `externalMissingClosedWhenCarrierKnowsAgain` | после `EXTERNAL_MISSING` перевозчик `ACCEPTED` → проблема `SYSTEM`, хотя доставка уже `ACKNOWLEDGED`; `acknowledged = false` |
| `pendingNotFoundIsMatch` | `PENDING`, `404` → `MATCH`, проблем нет |
| `unavailableIsErrorWithBasePause` | `lookupUnavailable` → строка `ERROR`, `next_reconcile_at = now + 5m` и при повторе тоже, состояние не меняется |
| `rowKeepsOurSide` | в строке `delivery_status` и `local_result` (каноническое содержимое нашего итога или пусто) на момент решения |
| `invalidAnswerIsErrorWithDetails` | `Invalid` → `ERROR`, `carrier_state` пуст, `details` с причиной, кодом и началом ответа, `carrier_response` дословно; проблем нет |
| `sendingAcknowledgedBeatsWorker` | операция в `SENDING` (воркер в `duringSubmit` запускает проверку) → `ACKNOWLEDGED` via `RECONCILIATION`; исход воркера не перезаписал |
| `pauseIsRecorded` | `reconciled_at = now`, `next_reconcile_at = now + 5m`; та же проверка ещё раз через 5 мин — `+10m`; сменился ответ — `+5m` |
| `refusedProblemRollsBackCheck` | `ProblemDao` (шпион) бросает → строки `reconciliation` нет, доставка прежняя |

**Код**
- `ReconciliationDao`: `insert`, `previous(requestId)`, `setTimes(requestId, reconciledAt, next)`; `lockOperation`
  (как `InboxDao.lockTarget`, по `request_id`) с нашим состоянием (доставка, итог заявки).
- `SendQueueDao` (или `DispatchOperationDao`): `acknowledgeByReconciliation(requestId, now)` — условный переход;
  `sendNow(requestId, now)` — для `UNKNOWN`.
- `ProblemService`: `onCarrierKnowsOperation(requestId, now)` — закрыть `EXTERNAL_MISSING` системой;
  `onExternalMissing(requestId, details, now)` — открыть.
- `Reconciler.check(requestId, runType, performedBy)` — `lookup` вне транзакции, затем `TransactionTemplate`: блокировка,
  правила, строка, эффекты, сроки. Эффекты `EXTERNAL_AHEAD` и `CONFLICT` пока — только строка (тесты шагов 6–7
  красные до них).

**Гейт:** `./gradlew :dispatch:test`.

## Шаг 6. `EXTERNAL_AHEAD` и закрытие `EXTERNAL_MISSING` результатом

**Тесты (сначала, красные)**

Интеграционные, `ReconcileAheadTests`:

| Тест | Ожидание |
|---|---|
| `failedExecutedAppliesResult` | подтверждение + `EXTERNAL_AHEAD`: итог в заявке сразу после `check` (без `processDue`), `version + 1`, запись `inbox` `RECONCILIATION` `APPLIED`, `applied_inbox_id` на неё, `DELIVERY_FAILED` закрыта, строка ссылается на запись |
| `invalidFeedRecordThenReconciliation` | `INVALID` из ленты (испорченная цена), сверка применяет итог; затем лента приносит исправную запись с тем же `messageId` → `STALE`, итог и `version` прежние |
| `repeatedAheadUsesExistingRecord` | запись `RECONCILIATION` с этим `messageId` уже есть (её разбор упал в `FAILED`) → новая не создаётся, строка ссылается на существующую, `details` с её статусом |
| `raceWithFeed` | `FakeCarrier.duringLookup` применяет итог через `TestResults.deliver` → `MATCH`, второй записи `APPLIED` нет |
| `processingFailureOfAheadRecordIsRetriedLater` | `InboxDao` (шпион) бросает при применении → запись `RECEIVED` с повтором, строка сверки записана; `processDue()` позже применяет |
| `externalMissingClosedByAppliedFeedResult` (в `MessageProblemsTests`) | открыта `EXTERNAL_MISSING`, по ленте приходит и применяется итог → проблема `SYSTEM`, хотя доставка уже `ACKNOWLEDGED` |

**Код**
- `InboxProcessor.process(UUID inboxId)` — в транзакции вызывающего (`MANDATORY`): та же логика, что у `processNext`,
  для записи по `id` (`claimById`: `FOR UPDATE`, только `RECEIVED`; если её успел разобрать фон — уже не `RECEIVED`,
  ничего не делаем); общая часть выносится, `processDue` не меняет поведения. Тест
  `processSkipsAlreadyDecidedRecord` в `InboxProcessorTests`.
- `InboxProcessor.finish`: при `APPLIED` — `problems.onCarrierKnowsOperation` независимо от `acknowledged`.
- `Reconciler`: эффект `EXTERNAL_AHEAD` — `ResultInbox.receive(result, RECONCILIATION)` или `InboxDao.findId`, затем
  `process(inboxId)`.

**Гейт:** `./gradlew :dispatch:test`.

## Шаг 7. `CONFLICT` и `MATCH` для конфликта

**Тесты (сначала, красные)**

Интеграционные, `ReconcileConflictTests`:

| Тест | Ожидание |
|---|---|
| `differentResultsOpenConflict` | `RECONCILIATION_CONFLICT` со ссылкой на строку, итог и `version` не изменены, доставка не изменена |
| `repeatedConflictRelinksOpenProblem` | вторая проверка → вторая строка, проблема одна, `reconciliation_id` и `details` — по второй строке |
| `carrierWithoutResultIsConflict` | у нас итог, у перевозчика `ACCEPTED` → `CONFLICT`, полей итога в строке нет |
| `matchClosesConflict` | открыт конфликт «у перевозчика итога нет», затем перевозчик исполнил с нашим итогом → `MATCH`, проблема `SYSTEM` |
| `sameAnswerAfterKeepOursOpensNothing` | конфликт закрыт `KEEP_OURS` (через `ProblemDao.closeById` прямо в тесте — действие появится в шаге 12), тот же ответ → строка `CONFLICT` с «решено ранее: проблема <id>», новой проблемы нет |
| `otherAnswerAfterKeepOursOpensNew` | после `KEEP_OURS` у перевозчика появился итог (или другой итог) → новая проблема |
| `ourResultChangedAfterKeepOursOpensNew` | после `KEEP_OURS` наш итог заменён (`version` вырос), ответ прежний → новая проблема |

**Код:** `ProblemService.onReconciliationConflict(requestId, reconciliationId, details, now)` — открыть, переставить
ссылку открытой или подавить: последняя закрытая `KEEP_OURS` проблема заявки ссылается на строку, у которой
содержимое итога перевозчика (или его отсутствие) и `local_result` совпадают с текущей строкой. Сравнение по
`local_result`, а не по `version` заявки: `version` растёт и от правки комментария или исполнителя.
`onReconciliationMatch(requestId, now)`; `ProblemDao.relink(problemId, reconciliationId, details)`.

**Гейт:** `./gradlew :dispatch:test`.

## Шаг 8. Фон: проходы, состав, пауза, журнал

**Тесты (сначала, красные)**

Интеграционные, `ReconcilerTests` (`Reconciler.runOnce(RunType)`):

| Тест | Ожидание |
|---|---|
| `picksDueOperationsOfTheBackgroundSet` | берёт `UNKNOWN`, `FAILED`, `ACKNOWLEDGED` без итога старше 2 мин, заявку с итогом и открытой `EXTERNAL_MISSING`, заявку с итогом и открытым `RECONCILIATION_CONFLICT` |
| `skipsOthers` | не берёт `ACKNOWLEDGED` моложе порога, с итогом без проблем, `PENDING`/`RETRY_WAIT`/`SENDING`, с `next_reconcile_at > now` |
| `perPassLimitAndOrder` | `ReconciliationDao.findDue(now, threshold, 2)` (лимит — параметр рабочего метода; `runOnce` передаёт `reconcilePerPass`) — две операции, сначала `NULL`, затем ранний `next_reconcile_at` |
| `unavailableStopsPass` | `lookupUnavailable` → одна строка `ERROR` с базовой паузой, остальные операции без строк |
| `startupRunsOnceWhenEnabled` | обработчик `ApplicationReadyEvent` вызывает `runOnce(STARTUP)` только при `enabled` (проверка на бине-обработчике с подменённым `Reconciler`, модульно: `ReconcilerStartupTest`) |
| `passIsLogged` | итоговая строка `INFO` с количеством по исходам (`OutputCaptureExtension`); пустой проход не пишется |

**Код:** `ReconciliationDao.findDue(now, threshold, limit)` (запрос состава с `EXISTS` по открытым проблемам; индекс
шага 2); `Reconciler.runOnce`, `@Scheduled(fixedDelayString = "${app.exchange.reconcile-interval:1m}")` с проверкой
`enabled`; слушатель `ApplicationReadyEvent`; `ExchangeProperties` + `reconcileInterval`, `reconcileThreshold`,
`reconcilePause`, `reconcilePauseMax`, `reconcilePerPass`; журнал по §2.11.

**Гейт:** `./gradlew :dispatch:test`.

## Шаг 9. Контракт и чтение: проблемы, действия, история сверок

**Тесты (сначала, красные)**

Модульные, `ProblemRulesTest` (дополнение):

| Тест | Ожидание |
|---|---|
| `externalMissingDecisions` | с итогом заявки — `[KEEP_OURS]`, без — `[]` |
| `reconciliationConflictDecisions` | с итогом у перевозчика — `[APPLY_CARRIER, KEEP_OURS]`, без — `[KEEP_OURS]` |
| `requestActions` | оператору всегда `RECONCILE`; `RETRY` при `FAILED`/`UNKNOWN`, при `ACKNOWLEDGED` + открытая `EXTERNAL_MISSING` без итога; не при `ACKNOWLEDGED` + `EXTERNAL_MISSING` с итогом |

Интеграционные, `ProblemQueryTests` (дополнение, там же проверяется карточка из (a); ответы проверяются по контракту
автоматически):

| Тест | Ожидание |
|---|---|
| `reconciliationConflictShowsCarrierVersionAsMessage` | `message` из строки сверки (`feedSeq` пуст, начало `carrier_response`), `currentResult` — наш итог, `actions` по правилам |
| `conflictWithoutCarrierResultHasEmptyResultFields` | поля итога в `message` пусты, `actions = [KEEP_OURS]` |
| `externalMissingShowsCurrentResult` | `currentResult` заполнен, `message` пуст |
| `problemsFilterByNewKinds` | `GET /api/problems?kind=EXTERNAL_MISSING` |
| `operatorCardHasReconciliations` | новые первыми, не больше 20, поля схемы `Reconciliation` |
| `operatorCardActionsIncludeReconcile` | `actions` содержит `RECONCILE` |
| `initiatorCardHasNoReconciliations` | поля нет, `actions = []` |

**Код**
- `contracts/dispatch-api.yaml`: `RequestAction` + `RECONCILE`; `ProblemKind` + два вида; схемы `Reconciliation`,
  `ReconciliationCarrierResult`; `RequestCard.reconciliations` (не обязательное, как `problems`); путь
  `POST /api/requests/{id}/reconcile` целиком (реализация — шаг 10); описания `retry`/`resolve` по §2.7; примеры.
- `contracts/carrier-api.yaml`: фраза про дубликат между источниками.
- `ProblemRules.decisions(kind, status, DecisionContext)` (спорная запись итоговая / итог у перевозчика / итог у
  заявки), `requestActions(DeliveryStatus, Set<ProblemKind> open, boolean hasResult)`.
- `ProblemQueryDao`: `LEFT JOIN reconciliation`, `message` для `RECONCILIATION_CONFLICT`, `currentResult` для обоих
  новых видов. `ReconciliationQueryDao.forRequest(id, 20)`, `RequestCard.forOperator(problems, actions,
  reconciliations)`.
- `make web-api-types`; web-тестовые данные (`requests/card/testData.ts`, `CreateRequestPage.test.tsx`) — `actions` у
  оператора с `RECONCILE`, где это проверяется.

**Гейт:** `./gradlew :dispatch:test`, `make contracts-lint`, `make web-build`.

## Шаг 10. «Сверить сейчас»

**Тесты (сначала, красные)**

Интеграционные, `ReconcileActionTests`:

| Тест | Ожидание |
|---|---|
| `operatorReconcilesAndSeesOutcome` | `200`, `Reconciliation` с `trigger = MANUAL`, `performedBy` — оператор; строка с автором |
| `aheadResultVisibleRightAfterResponse` | перевозчик `EXECUTED` → ответ `EXTERNAL_AHEAD`, следующий `GET` карточки уже с итогом |
| `unavailableIsAnOutcome` | `lookupUnavailable` → `200`, `outcome = ERROR`, строка записана |
| `sameKeyReplaysWithoutAskingCarrier` | тот же ключ → тот же ответ, `Idempotent-Replayed: true`, `FakeCarrier` вызван один раз; так же для `ERROR` |
| `sameKeyOtherRequestIsRejected` | `422 IDEMPOTENCY_KEY_REUSED` |
| `missingKeyIs400` | `400 IDEMPOTENCY_KEY_REQUIRED` |
| `initiatorOwnIs403`, `initiatorForeignIs404`, `otherServiceOperatorIs404` | как у `retry` (временный оператор юга, как в `ProblemAccessTests`) |
| `unknownOrMalformedIdIs404` | `404` |
| `manualIgnoresDueTime` | `next_reconcile_at` в будущем — проверка всё равно выполняется |

**Код:** `OperatorActions.reconcile` (`@Transactional`: `getCard` → `requireOperator` → `idempotency.execute(…,
"RECONCILE", {requestId}, Reconciliation.class, () -> reconciler.checkInTransaction(...))`); у `Reconciler` — вариант
одной проверки внутри текущей транзакции (`lookup`, затем блокировка), общий код с фоном; `OperatorActionController`
— `POST /api/requests/{id}/reconcile`; журнал `INFO`.

**Гейт:** `./gradlew :dispatch:test`.

## Шаг 11. «Повторить отправку» для потерянной операции

**Тесты (сначала, красные)**

Интеграционные, `RetryActionTests` (дополнение):

| Тест | Ожидание |
|---|---|
| `retryAcknowledgedWithExternalMissing` | `PENDING`, `retry_from = attempts`, `attempts` прежний, `next_attempt_at = now`, `acknowledged_*` пусты, `reconciled_at`/`next_reconcile_at` пусты, `EXTERNAL_MISSING` — `RETRIED` с автором; `SendWorker.runOnce()` шлёт тот же `operationId` |
| `retryAcknowledgedWithResultIs409` | `EXTERNAL_MISSING` при итоге → `409 INVALID_STATE`, ничего не изменилось |
| `retryAcknowledgedWithoutProblemIs409` | `ACKNOWLEDGED` без `EXTERNAL_MISSING` → `409` |
| `retryFailedClearsReconcileTimes` | старый случай (a): сроки сверки очищены |

**Код:** `SendQueueDao.retryByOperator` — условие расширяется (`ACKNOWLEDGED` с `EXISTS` открытой `EXTERNAL_MISSING` и
`business_status` не итог, проверка под блокировкой строки операции), очистка `acknowledged_*` и сроков сверки;
`OperatorActions.retry` — закрыть `EXTERNAL_MISSING` `RETRIED`; текст `409`.

**Гейт:** `./gradlew :dispatch:test`.

## Шаг 12. Решения по новым видам

**Тесты (сначала, красные)**

Интеграционные, `ResolveReconciliationProblemTests`:

| Тест | Ожидание |
|---|---|
| `applyCarrierReplacesResult` | итог перевозчика в заявке, `version + 1`, запись `inbox` `RECONCILIATION` `APPLIED` с причиной «Applied by operator decision <problemId>», `applied_inbox_id` на неё, проблема `APPLY_CARRIER` с автором и комментарием |
| `feedRepeatAfterApplyCarrierIsStale` | затем лента приносит ту же запись → `STALE`; приносит нашу прежнюю запись → `STALE`; проблем не открыто |
| `applyCarrierUsesExistingRecord` | запись `RECONCILIATION` с этим `messageId` уже есть → она и применена, второй нет |
| `applyCarrierWithoutCarrierResultIs409` | конфликт «у перевозчика итога нет» → `409 INVALID_STATE` |
| `keepOursClosesConflict` | итог и `version` прежние, проблема `KEEP_OURS` |
| `keepOursOnExternalMissingWithResult` | проблема закрыта `KEEP_OURS` |
| `keepOursOnExternalMissingWithoutResultIs409` | `409`, доступное действие — `RETRY` |
| `commentRequired` | без комментария → `400` |
| `concurrentDecisionsOneWins` | второе решение по закрытой → `409` |

**Код:** `OperatorActions.resolve` — для `RECONCILIATION_CONFLICT` + `APPLY_CARRIER`: блокировка операции,
`result` из `carrier_response` строки проблемы → `ResultInbox.receive(…, RECONCILIATION)` или `InboxDao.findId`, затем
`OperatorResults.applyChosen`, затем `InboxDao.recordOutcome(id, APPLIED, "Applied by operator decision …")`;
`ProblemDao.reconciliationOf(problemId)`.

**Гейт:** `./gradlew :dispatch:test`.

## Шаг 13. e2e

**Тесты** — `e2e/ReconciliationE2ETest` (против стенда; режимы сбоев выключаются до и после теста):

| Тест | Ожидание |
|---|---|
| `reconcileCatchesUpDelayedResult` | `delayed`: заявка, ждать `ACKNOWLEDGED`, «Сверить сейчас» до публикации в ленте → `EXTERNAL_AHEAD`, итог в карточке сразу; `executionCount = 1`; после публикации в ленте (≤ 90 с) итог и `version` прежние |
| `reconcileOfFinishedRequestIsMatch` | обычная заявка с итогом → `MATCH`, проверка в `reconciliations` |
| `initiatorCannotReconcile` | `403`, в карточке нет `reconciliations` |

`CarrierClient` в e2e не меняется (режим `delayed` уже есть).

**Гейт:** `make up && make e2e`.

## Шаг 14. Документы и полный гейт

- `CLAUDE.md`: пакет `exchange/reconcile`, `lookup` у `FakeCarrier`, V11, сверка реализована (ссылка на спеку),
  новые эндпоинты.
- `README.md`: сверка (когда идёт, исходы, пауза), «Сверить сейчас», новые виды и решения, `curl`-пример.
- Полный гейт: `./gradlew build`, `make up && make e2e`, `make contracts-lint`, `make web-build`, `make web-e2e`.

## Риски

- **Миграция на стенде.** V11 пересоздаёт ограничения `problem` и индекс `inbox` на непустой базе: старые строки им
  удовлетворяют (новые колонки пусты, `reconciliation_id IS NULL` у старых видов). Проверяется `make up` поверх
  существующей базы в шаге 2.
- **Транзакция ручной сверки держит соединение** на время `lookup` (до 7 с). Пул Hikari по умолчанию — 10; массовое
  нажатие «Сверить сейчас» может его выбрать. Для демо приемлемо; в журнале и README — как известное ограничение.
- **`process(inboxId)` внутри чужой транзакции:** исключение вне точки сохранения откатит и строку сверки. Это
  правильно (проверка повторится), тест `processingFailureOfAheadRecordIsRetriedLater` закрепляет, что сбой применения
  не откатывает строку.
- **Один `@IntegrationTest`-контекст:** свойства в тесте не меняются; лимит за проход проверяется на
  `ReconciliationDao.findDue`, где он — параметр рабочего метода, без тестовых перегрузок.
