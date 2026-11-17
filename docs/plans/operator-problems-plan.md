# План: проблемы обмена и действия оператора

Основание: спека `docs/specs/operator-problems.md` (опрос Q1–Q23, проверка спеки 16.11.2026). Ветка
`operator-problems` от `main`. Работа по TDD: в каждом шаге сначала пишутся тесты шага и запускаются, чтобы увидеть,
что они падают **по нужной причине** (красные), затем код до зелёного, затем рефакторинг. Рабочего кода без падающего
теста нет. Один шаг — один коммит; после каждого шага проходит гейт шага (`./gradlew :dispatch:test` или указанный
в шаге), в конце — полный гейт.

## 0. Исходное состояние

- Миграции V1–V8. Таблицы `problem` нет; в `dispatch_operation` нет `retry_from` и `applied_inbox_id`.
- `SendWorker`: `RetryPolicy.canRetry/backoff` получают абсолютный `attempts` (`claimed.attempt()`,
  `expired.attempt()`). `SendQueueDao.claimNext` и `findExpired` возвращают `attempts`, но не `retry_from`.
- `InboxProcessor.decide` под точкой сохранения сам пишет исход (`recordOutcome`); `InboxDao.applyToOperation` —
  два `UPDATE`, результат второго (подтверждение доставки) не возвращается; `appliedContent` ищет строку `inbox` в
  `APPLIED` с тем же `sequence`. `INVALID` не ищет операцию.
- `TestRows.deleteRequestData` удаляет `idempotency_key`, `inbox`, `exchange_attempt`, `dispatch_operation`,
  `delivery_request` — именно в этом порядке. С FK `dispatch_operation.applied_inbox_id → inbox` и
  `problem → inbox / delivery_request` порядок придётся поменять (шаги 2–3).
- Тест миграции с данными уже есть по образцу: `InboxPayloadMigrationTests` (миграции до `target` в отдельной схеме,
  вставка строк, затем следующая миграция).
- `IdempotencyService.execute(userId, key, operation, request, …)` хэширует `request`; вызывающий решает, что в нём.
- Порядок миграций: `V9__problems.sql` раньше `V10__operation_retry_budget.sql` — Flyway без `outOfOrder` не
  применит V9 после V10 на стенде, поэтому шаги идут в порядке номеров.

## Шаг 1. Документы дизайна

**Что делаем**
- `docs/dispatch-design.md`:
  - §4 — таблица `problem` (колонки, ограничения, индексы), колонки `dispatch_operation.retry_from`,
    `applied_inbox_id`; строка «Этап 2 добавит…» заменяется описанием;
  - §5 — `409 PAYLOAD_MISMATCH` → `DELIVERY_FAILED` (без вида `PAYLOAD_CONFLICT`); «Повторы»: повтор оператором
    начинает новый бюджет (`retry_from`), `attempts` сквозной; «Обработка результатов»: сравнение с применённой
    записью `applied_inbox_id`, проблемы открываются после точки сохранения; без «разобрать заново»;
  - §7 — виды и действия по спеке (`MESSAGE_FAILED`, без `PAYLOAD_CONFLICT`, четыре действия, `actions`);
- спека: статус «согласовано», ссылка на этот план.

**Тесты:** нет (только документы).

**Готово, когда:** дизайн и спека не противоречат друг другу.

## Шаг 2. Таблица `problem` и перенос состояния (V9)

**Тесты (сначала, красные)**

Интеграционные, `SchemaMigrationTests` (красные: таблицы нет). Помощник `insertProblem(...)`:

| Тест | Ожидание |
|---|---|
| `allMigrationsAreApplied` (меняется) | список с `9 problems` |
| `problemKindMustBeKnown`, `problemStatusMustBeKnown`, `problemDecisionMustBeKnown` | отказ, имя ограничения `problem_kind_valid` / `_status_valid` / `_decision_valid` |
| `openProblemHasNoResolution`, `resolvedProblemHasDecisionAndTime` | `problem_resolution_complete` |
| `systemDecisionHasNoAuthor`, `operatorDecisionHasAuthor` | `problem_system_has_no_author` |
| `conflictDecisionNeedsComment`, `blankCommentIsRejected` | `problem_decision_needs_comment`, `problem_comment_not_blank` |
| `deliveryProblemNeedsRequestAndNoRecord`, `messageProblemNeedsRecord` | `problem_source_matches_kind` |
| `decisionMustFitKind` (`RETRIED` у `RESULT_CONFLICT`, `APPLY_CARRIER` у `INVALID_MESSAGE`, `SYSTEM` у `INVALID_MESSAGE`) | `problem_decision_matches_kind` |
| `secondOpenDeliveryProblemOfAKindIsRejected` | уникальность `problem_open_per_request_kind`; после закрытия первой — вторая принимается |
| `secondProblemForARecordIsRejected` | `problem_per_inbox_record` |
| `problemWithoutRequestIsAccepted` | `INVALID_MESSAGE` с `inbox_id` и без `request_id` |
| `problemIndexesExist` | `problem_list_idx`, `problem_request_idx` в `pg_indexes` |

Интеграционные, новый `ProblemsMigrationTests` (образец — `InboxPayloadMigrationTests`; красный: миграции нет):

| Тест | Ожидание |
|---|---|
| `existingStateGetsItsProblems` | до `V8`: операции в `UNKNOWN`, `FAILED`, `ACKNOWLEDGED`; записи `inbox` `INVALID` (с известным и с незнакомым `operation_id`), `FAILED`, `CONFLICT`, `APPLIED`; после `V9` — по одной открытой проблеме: `DELIVERY_UNKNOWN`, `DELIVERY_FAILED` (`details` = `last_error`), `INVALID_MESSAGE` с заявкой и без, `MESSAGE_FAILED`, `RESULT_CONFLICT` (`details` = `reason`); у `ACKNOWLEDGED` и `APPLIED` — нет |

**Код**
- `db/migration/V9__problems.sql`: таблица, ограничения, индексы по спеке §2.2; перенос состояния `INSERT … SELECT`
  с `uuidv7()`.
- `support/TestRows.deleteRequestData`: `DELETE FROM problem` первым.

**Гейт шага:** `./gradlew :dispatch:test`.

## Шаг 3. Бюджет повторов и применённая запись в схеме (V10), бюджет в `SendWorker`

**Тесты (сначала, красные)**

Интеграционные, `SchemaMigrationTests`:

| Тест | Ожидание | Почему красный |
|---|---|---|
| `allMigrationsAreApplied` | с `10 operation retry budget` | миграции нет |
| `retryFromCannotExceedAttempts`, `retryFromCannotBeNegative` | `dispatch_operation_retry_from_range` | колонки нет |
| `appliedRecordMustExist` | FK `applied_inbox_id` | колонки нет |

Интеграционные, `ProblemsMigrationTests` (или `OperationRetryBudgetMigrationTests`):

| Тест | Ожидание |
|---|---|
| `appliedRecordIsBackfilled` | до `V9`: операция с записями `APPLIED` `sequence` 1 и 2 и `STALE` 2; после `V10` — `applied_inbox_id` = запись `APPLIED` с `sequence = 2`; `retry_from = 0` у всех |

Интеграционные, `SendWorkerTests` (строка операции готовится напрямую: `attempts = 8`, `retry_from = 8`,
`UNKNOWN`, к отправке сейчас):

| Тест | Ожидание | Почему красный |
|---|---|---|
| `retryBudgetCountsFromRetryFrom` | ответ — таймаут: `UNKNOWN`, `attempts = 9`, пауза 4–6 с (как после 1-й попытки), не `FAILED` | политика видит абсолютные 9 > 8 → `FAILED` |
| `budgetRunsOutAfterMaxAttemptsFromRetryFrom` | `attempts = 15`, `retry_from = 8`, таймаут → `FAILED`, `Attempts exhausted (8)` | то же, плюс текст с абсолютным номером |
| `attemptNumbersContinueAfterANewBudget` | `exchange_attempt` получает `attempt_no = 9` | сейчас верно — тест-страж, должен остаться зелёным |
| `recoveryUsesTheBudgetToo` | прерванная отправка при `attempts = 9`, `retry_from = 8` → `UNKNOWN` с паузой 4–6 с | `recover` видит 9 → `FAILED` |

**Код**
- `db/migration/V10__operation_retry_budget.sql`: `retry_from integer NOT NULL DEFAULT 0` +
  `dispatch_operation_retry_from_range`; `applied_inbox_id uuid` + FK `dispatch_operation_applied_inbox_fk`;
  заполнение `applied_inbox_id`.
- `SendQueueDao`: `claimNext` (`RETURNING … o.retry_from`), `findExpired` — `retry_from` в `Claimed` / `Expired`.
- `SendWorker`: номер внутри бюджета `attempt − retryFrom` в `retryOrFail` и `recover`, в тексте `Attempts
  exhausted (N)` — тоже он.
- `TestRows.deleteRequestData`: `UPDATE dispatch_operation SET applied_inbox_id = NULL` перед удалением `inbox`
  (или удаление `dispatch_operation` раньше `inbox`).

**Гейт шага:** `./gradlew :dispatch:test`.

## Шаг 4. Применённая запись в разборе

**Тесты (сначала, красные)**

Интеграционные, `InboxProcessorTests`:

| Тест | Ожидание | Почему красный |
|---|---|---|
| `appliedRecordIsRemembered` | после `APPLIED` у операции `applied_inbox_id` = эта запись; после следующего `APPLIED` — новая | колонку никто не пишет |
| `sameSequenceIsComparedWithTheAppliedRecord` | `applied_inbox_id` вручную указывает на запись `CONFLICT` с содержимым B (так сделает решение оператора в шаге 9); приходит B с новым `messageId` — `STALE`; приходит прежний A с новым `messageId` — `CONFLICT` | `appliedContent` ищет `APPLIED` и находит A |

Без изменений, зелёные: `ResultRulesTest`, остальные `InboxProcessorTests`, `FeedPollerTests`.

**Код**
- `InboxDao.applyToOperation(operationId, sequence, inboxId, now)`: ставит `applied_inbox_id`.
- `InboxDao.appliedContent(operationId, sequence)` → содержимое записи `applied_inbox_id`, если её `sequence`
  равен пришедшему, иначе пусто.

**Гейт шага:** `./gradlew :dispatch:test`.

## Шаг 5. Правила проблем и проблемы доставки

**Тесты (сначала, красные)**

Модульные, `problem/ProblemRulesTest` (красные: класса нет):

| Тест | Ожидание |
|---|---|
| `deliveryStatusOpensOrCloses` (параметризованный по всем `DeliveryStatus`) | `UNKNOWN` → открыть `DELIVERY_UNKNOWN`; `FAILED` → открыть `DELIVERY_FAILED`; `ACKNOWLEDGED` → закрыть обе; остальные — ничего |
| `recordOutcomeOpens` (по всем исходам разбора) | `INVALID` → `INVALID_MESSAGE`, `FAILED` → `MESSAGE_FAILED`, `CONFLICT` → `RESULT_CONFLICT`, `APPLIED`/`STALE` — ничего |
| `availableDecisions` | `RESULT_CONFLICT` с итоговой спорной записью → `APPLY_CARRIER`, `KEEP_OURS`; с `IN_PROGRESS` → `KEEP_OURS`; `INVALID_MESSAGE`, `MESSAGE_FAILED` → `ACKNOWLEDGED`; `DELIVERY_*` и любая `RESOLVED` → пусто |
| `requestActions` | доставка `FAILED` / `UNKNOWN` → `RETRY`; остальные → пусто |

Интеграционные, новый `problem/DeliveryProblemsTests` (`FakeCarrier`, `SendWorker.runOnce()`):

| Тест | Ожидание |
|---|---|
| `timeoutOpensDeliveryUnknown` | `UNKNOWN` + открыта `DELIVERY_UNKNOWN`, `details` = ошибка |
| `confirmationByResendClosesIt` | таймаут, затем `202` → `ACKNOWLEDGED`, проблема `RESOLVED`, `SYSTEM`, `resolved_by` пуст |
| `confirmationByResultClosesIt` | таймаут, затем `TestResults.deliver` → закрыта системой |
| `repeatedTimeoutsKeepOneProblem` | три таймаута подряд → одна открытая |
| `exhaustedAfterUnknownOpensBoth` | попытки кончились таймаутами → открыты `DELIVERY_UNKNOWN` и `DELIVERY_FAILED` |
| `permanentErrorOpensDeliveryFailed` (`4xx`, `PAYLOAD_CONFLICT`) | `DELIVERY_FAILED`, `details` = `last_error` |
| `interruptedSendOpensDeliveryUnknown`, `interruptedLastAttemptOpensBoth` | восстановление → проблемы по спеке |
| `lateWorkerOpensNothing` | опоздавший исход (условный `UPDATE` не сработал) — проблем не прибавилось |
| `refusedProblemRollsBackTheOutcome` | шпион `ProblemDao.open` бросает `DataIntegrityViolationException`: операция осталась `SENDING`, проблемы нет |
| `refusedProblemStopsRecovery` (`OutputCaptureExtension`) | то же в восстановлении: `runOnce` бросает, ничего не отправлено, состояние не изменилось |

`ProblemDao` — шпион Mockito в каждом `@IntegrationTest`, сброс после теста (как `InboxDao`, `support/`).

**Код**
- пакет `problem`: `ProblemKind`, `ProblemStatus`, `ProblemDecision`, `ProblemRules` (чистые), `ProblemDao`
  (`open` — `INSERT … ON CONFLICT DO NOTHING`, `closeBySystem(requestId, kinds, now)`), `ProblemService
  .onDeliveryStatus`;
- `SendWorker.record` и `recover`: вызов после сработавшего перехода; журнал по спеке §2.3.

**Гейт шага:** `./gradlew :dispatch:test`.

## Шаг 6. Проблемы записей ленты

**Тесты (сначала, красные)**

Интеграционные, новый `problem/MessageProblemsTests` (`TestFeed`, `FeedPoller.runOnce()`,
`InboxProcessor.processDue()`):

| Тест | Ожидание |
|---|---|
| `invalidRecordOfAKnownRequestOpensProblemWithIt` | цена вне пределов, известный `operationId` → `INVALID_MESSAGE` с заявкой |
| `invalidRecordOfAnUnknownOperationHasNoRequest`, `recordWithoutMessageIdHasNoRequest` | без заявки |
| `failedRecordOpensMessageFailed` | шпион `InboxDao` бросает до исчерпания попыток → `MESSAGE_FAILED` |
| `conflictOpensResultConflict` | тот же `sequence`, другое содержимое; итог есть, приходит новее → по `RESULT_CONFLICT` |
| `duplicateOpensNothing` | повтор `messageId` |
| `resultConfirmingDeliveryClosesDeliveryProblems` | операция `FAILED` с открытыми `DELIVERY_*`, приходит результат → закрыты системой |
| `refusedProblemDoesNotBlameTheCarrier` | шпион `ProblemDao.open` бросает `DataIntegrityViolationException` на `CONFLICT`: запись осталась `RECEIVED` (не `INVALID`), проход остановлен, журнал `ERROR` |
| `refusedClosingDoesNotApplyTheResult` | то же на закрытии при подтверждении: итог не применён, запись `RECEIVED` |

Без изменений, зелёные: все `InboxProcessorTests` (исходы и журнал те же).

**Код**
- `InboxProcessor.decide` возвращает исход (`Decision`, причина, подтвердилась ли доставка, id заявки) и больше не
  пишет его; `processNext` после точки сохранения пишет исход и вызывает `ProblemService.onInboxOutcome` /
  `onDeliveryStatus(ACKNOWLEDGED)`; путь `DataIntegrityViolationException` и `recordFailed` — так же.
- `InboxDao.applyToOperation` возвращает, сработало ли подтверждение; `InboxDao.requestOf(operationId)` для
  `INVALID` с разобранным `operationId`.

**Гейт шага:** `./gradlew :dispatch:test`.

## Шаг 7. Чтение: список проблем и карточка

**Тесты (сначала, красные)**

Интеграционные, новый `problem/ProblemQueryTests` (MockMvc, ответы сверяются с контрактом автоматически; красные:
пути нет в контракте и в коде):

| Тест | Ожидание |
|---|---|
| `listsOpenProblemsNewestFirstByDefault` | только `OPEN`, `opened_at DESC, id DESC` |
| `filtersByStatusAndKind`, `pagesAndCounts` | `status=RESOLVED/ALL`, несколько `kind`, `size` до 100; `size=101` — `400` |
| `problemFieldsPerKind` | `DELIVERY_*`: `request`, `operationId`, `message = null`; `INVALID_MESSAGE`: `message` с разобранными полями и `payloadExcerpt` (обрезан до 2000, управляющие символы убраны); `RESULT_CONFLICT`: `message` + `currentResult`; `actions` по `ProblemRules` |
| `resolvedProblemShowsWhoAndHow` | `resolvedBy`, `decision`, `comment`; у системы `resolvedBy = null` |
| `cardShowsProblemsAndActionsToOperator` | `problems` новые сверху, `actions = ["RETRY"]` при `FAILED` |

Интеграционные, новый `problem/ProblemAccessTests` (часть чтения):

| Тест | Ожидание |
|---|---|
| `initiatorCannotListProblems` | `403 FORBIDDEN` |
| `operatorSeesOnlyOwnServiceAndRecordsWithoutRequest` | проблем чужой службы нет; проблема без заявки видна обеим службам |
| `initiatorCardHasNoProblems` | `problems` нет в ответе (`null`), `actions = []` |

Модульный: `ContractCheckTests` уже требует, чтобы каждый путь был в контракте.

**Код**
- `contracts/dispatch-api.yaml`: `GET /api/problems`, схемы `Problem`, `ProblemKind`, `ProblemStatus`,
  `ProblemDecision`, `ProblemMessage`, `RequestAction`; в `RequestCard` — `problems`, `actions`;
  `make contracts-lint`, `make web-api-types`.
- `problem/ProblemController` (`GET /api/problems`), `ProblemQueryDao` (список одним запросом с `JOIN` к заявке и
  `inbox`; область — служба оператора или `request_id IS NULL`).
- `RequestQueryService.getCard`: `problems` и `actions` для оператора.

**Гейт шага:** `./gradlew :dispatch:test`, `make contracts-lint`, `cd web && pnpm api:check && pnpm typecheck`.

## Шаг 8. «Повторить отправку»

**Тесты (сначала, красные)**

Интеграционные, новый `problem/RetryActionTests`:

| Тест | Ожидание |
|---|---|
| `retryOfFailedStartsANewBudget` | `FAILED` (попытки кончились) → `200`, карточка; `PENDING`, `attempts` прежний, `retry_from = attempts`, `next_attempt_at = now`, `last_error` пуст, `operationId` прежний; `DELIVERY_FAILED` — `RETRIED`, автор; `SendWorker.runOnce()` отправляет попытку `attempts + 1`, `ACKNOWLEDGED`, `DELIVERY_UNKNOWN` (если была) закрыта системой |
| `retryOfUnknownSendsNowAndKeepsTheAmbiguity` | `UNKNOWN` → остаётся `UNKNOWN`, отправка сейчас, `DELIVERY_UNKNOWN` открыта до `202` |
| `retryIsRefusedInOtherStates` (`PENDING`, `SENDING`, `RETRY_WAIT`, `ACKNOWLEDGED`) | `409 INVALID_STATE`, строка операции не изменилась |
| `newBudgetGetsFullAttemptsAndShortPause` | после повтора таймаут → пауза 4–6 с, ещё 8 попыток до `FAILED` |
| `lateWorkerAfterRetryIsFenced` | исход по старому номеру попытки после повтора — не записан |
| `sameKeyReplaysTheResponse` | второй вызов с тем же ключом — тот же ответ, `Idempotent-Replayed: true`, `retry_from` не сдвинут |
| `sameKeyForAnotherRequestIsRejected` | `422 IDEMPOTENCY_KEY_REUSED`, вторая заявка не тронута |
| `keyIsRequired` | `400 IDEMPOTENCY_KEY_REQUIRED` |

`ProblemAccessTests` (часть «Повторить»): инициатор — `403` на свою, `404` на чужую; оператор другой службы — `404`.

**Код**
- контракт: `POST /api/requests/{id}/retry` с `Idempotency-Key`, ответ `RequestCard`, `409`;
- `SendQueueDao.retryByOperator(requestId, now)` — условный `UPDATE … WHERE delivery_status IN ('FAILED',
  'UNKNOWN')`;
- `ProblemService.retry(user, requestId, key)` в одной транзакции: права, `IdempotencyService` (запрос — `{requestId}`),
  обновление, закрытие `DELIVERY_FAILED` (`RETRIED`), журнал; `RequestCommandController` или `ProblemController` —
  эндпоинт (по пути — в `request`).

**Гейт шага:** `./gradlew :dispatch:test`, `make contracts-lint`, `make web-api-types`.

## Шаг 9. Решение проблемы: конфликт и «Разобрано»

**Тесты (сначала, красные)**

Интеграционные, новый `problem/ResolveProblemTests`:

| Тест | Ожидание |
|---|---|
| `keepOursClosesWithoutChangingTheResult` | итог и `version` прежние; `KEEP_OURS`, автор, комментарий (после `strip`), `resolved_at` |
| `applyCarrierReplacesTheResult` | итог заявки из спорной записи, `version + 1`, `last_applied_seq = max`, `applied_inbox_id` = спорная запись, запись `inbox` осталась `CONFLICT` |
| `appliedRecordFromTheFeedAgainIsStale` | после `APPLY_CARRIER` та же спорная запись с новым `messageId` → `STALE`, новой проблемы нет |
| `applyCarrierNeedsAFinalRecord` | спорная `IN_PROGRESS` → `409 INVALID_STATE` |
| `editStartedBeforeTheDecisionConflicts` | `PATCH` с версией до решения → `409 VERSION_CONFLICT` |
| `acknowledgeClosesMessageProblems` | `INVALID_MESSAGE` (с заявкой и без), `MESSAGE_FAILED` → закрыты |
| `decisionMustFitKind` | `ACKNOWLEDGED` для конфликта, любое решение для `DELIVERY_*`, `RETRIED`/`SYSTEM` через `resolve` → `409 INVALID_STATE` |
| `resolvedProblemCannotBeResolvedAgain` | `409`, первое решение сохранено |
| `concurrentDecisionsOneWins` | два потока с разными ключами → один `200`, другой `409`, одна запись решения |
| `commentIsValidated` | пусто, пробелы, 2001 символ, неизвестное решение → `400 VALIDATION_FAILED` с полем |
| `sameKeyReplays`, `sameKeyForAnotherProblemIsRejected`, `keyIsRequired` | как в шаге 8 |

`ProblemAccessTests` (часть `resolve`): инициатор — `403`; оператор другой службы — `404`; проблему без заявки
закрывает оператор любой службы.

**Код**
- контракт: `POST /api/problems/{id}/resolve`, `ResolveProblemBody`, ответ `Problem`;
- `ProblemService.resolve`: права, идемпотентность (запрос — `{problemId, decision, comment}`), для
  `APPLY_CARRIER` — блокировка строки операции, затем условное закрытие проблемы, применение записи через
  `InboxDao.applyToRequest` и `applyToOperation` (с `applied_inbox_id`); порядок блокировок по спеке §2.4;
- `ProblemDao.closeByOperator` — `WHERE status = 'OPEN'`.

**Гейт шага:** `./gradlew :dispatch:test`, `make contracts-lint`, `make web-api-types`.

## Шаг 10. e2e

**Тесты (против стенда, `make up` с этой веткой)**

`e2e/ProblemsE2ETest` (режимы сбоев выключаются до и после каждого теста, уникальное описание груза):

| Тест | Ожидание |
|---|---|
| `lostResponseShowsAndClosesDeliveryUnknown` | `loseResponse` + `delayed`; у оператора в карточке появилась `DELIVERY_UNKNOWN`, затем она `RESOLVED`/`SYSTEM`; у эмулятора `executionCount = 1` |
| `malformedRecordBecomesAProblemAndIsAcknowledged` | `malformed`; в `GET /api/problems` — `INVALID_MESSAGE` по заявке; `resolve` `ACKNOWLEDGED` с комментарием → закрыта, в списке `OPEN` её нет |
| `initiatorHasNoAccessToProblems` | `403` на список, `retry`, `resolve` |

Красные до шага 10 не бывают: эндпоинты уже есть; тест пишется и запускается, чтобы проверить стенд. Если зелёный
сразу — это ожидаемо (e2e проверяет сборку целиком, а не новый код).

**Гейт шага:** `make up && make e2e`.

## Шаг 11. Документы и полный гейт

**Что делаем**
- `CLAUDE.md`: пакет `problem`, `ProblemDao` — шпион в тестах, новые эндпоинты, `retry_from` и `applied_inbox_id`
  в описании `exchange`; миграции V1–V10.
- README: проблемы и действия оператора, `curl`-примеры `retry` и `resolve`.
- `docs/manual-testing.md`: ничего — ручные проверки проблем и конфликтов добавляет спека (c).

**Полный гейт:** `./gradlew build`; `make up && make e2e`; `make contracts-lint`; `make web-build` (только типы
изменились, но гейт `web/` обязателен при изменении `schema.d.ts`).

## Риски

- **Шаг 6 трогает сердце разбора** (`decide` / `processNext`). Все существующие `InboxProcessorTests` должны остаться
  зелёными без правки ожиданий — это и есть проверка, что рефакторинг ничего не поменял.
- **Перенос в V9** использует `uuidv7()`: PostgreSQL 18 в Testcontainers и в `docker-compose.yml`
  (`postgres:18-alpine`) — проверено в шаге 2 самим тестом миграции.
- **`refusedProblemStopsRecovery`** закрепляет поведение «отправка встаёт целиком» как намеренное (спека §2.3);
  если на ревью решим иначе — это решение Q2, а не правка плана.
