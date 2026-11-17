# План: история заявки

Основание: спека `docs/specs/request-history.md` (согласована 18.11.2026, Q1–Q6 приняты как предложено). Ветка
`request-history` от `main`. Работа по TDD: в каждом шаге сначала пишутся тесты шага и запускаются, чтобы увидеть,
что они падают **по нужной причине** (красные), затем код до зелёного, затем рефакторинг. Рабочего кода без
падающего теста нет. Один шаг — один коммит; после каждого шага — гейт шага, в конце — полный гейт.

## 0. Исходное состояние

- Миграции V1–V12. `SchemaMigrationTests.allMigrationsAreApplied` — список до `12`. Номер новой миграции — следующий
  свободный на момент шага 2 (сейчас `13`).
- Писатели `delivery_request`:
  - `RequestService.update` — `requests.save` (Spring Data JDBC, `@Version`); пустая правка ничего не пишет;
  - `InboxDao.applyToRequest` — `UPDATE … version = version + 1` всегда, даже если итог тот же. Вызывают
    `InboxProcessor.decide` (под savepoint: там пишутся только данные перевозчика) и `OperatorResults.applyChosen`;
  - создание — `RequestService.createNew`, `version = 0`.
- `SendQueueDao.retryByOperator` — один `UPDATE`, возвращает `boolean`; прежний статус доставки не возвращает.
  `UNKNOWN` остаётся `UNKNOWN`.
- `inbox`: итог обработки пишут `InboxDao.recordOutcome` (`UPDATE … processing_status = :status`) и `markFailed`
  (`FAILED`); времени обработки нет.
- `TestRows.deleteRequestData` удаляет таблицы заявок по порядку FK.
- Контракт: `RequestCard` без истории; пути `/api/requests/{id}/history` нет.
- web: карточка `requests/card/`; истории нет.

**Новый пакет** `com.acme.dispatch.history`:
- `RequestEventDao` — только `insert` (неизменяемость: других методов записи нет);
- `Change`, `Changes` — чистая сборка `{field, from, to}` из старых и новых значений;
- `HistoryQueryDao` — лента одним запросом (`UNION ALL` источников, курсор);
- `HistoryRules` — чистые правила: какие виды видит роль, порядок, курсор;
- `HistoryController` — `GET /api/requests/{id}/history`.

Его используют `request` (`RequestService`), `exchange/inbox` (`InboxDao`) и `problem` (`OperatorActions`);
`history` не зависит от них.

Запись `RESULT_CHANGED` идёт под savepoint `InboxProcessor` вместе с `applyToRequest`. Её переменная часть — итог
перевозчика, тот же, что пишется в заявку; структура события (вид, версия, `inbox_id`) задаётся кодом и проверяется
тестами, так что отказ БД на ней — ошибка кода, а не данных.

## Шаг 1. Документы дизайна

**Что делаем**
- `docs/dispatch-design.md` §4: таблица `request_event` (колонки, ограничения), `inbox.processed_at`, V13 в списке
  миграций; правило «изменение заявки = новая версия = одно событие в той же транзакции».
- `docs/dispatch-design.md` §8: вместо `audit_log` — история заявки по спеке; лента, видимость по ролям.
- `docs/dispatch-design.md` §7 (действия оператора): «в общую историю — с `audit_log`» → событие `DELIVERY_RETRIED`
  и `RESULT_CHANGED` от оператора.

**Тесты:** нет. **Готово, когда:** дизайн и спека не противоречат друг другу.

## Шаг 2. Схема V13

**Тесты (сначала, красные)** — интеграционные, `SchemaMigrationTests`:

| Тест | Что проверяет |
|---|---|
| `allMigrationsAreApplied` | список до `13` |
| `requestEventIsAccepted` | `EDITED` с версией и автором, `RESULT_CHANGED` с версией и `inbox_id` без автора, `DELIVERY_RETRIED` без версии с автором — принимаются |
| `requestEventKindIsChecked` | `kind = 'DELETED'` отвергается (`request_event_kind_valid`) |
| `requestEventVersionMatchesKind` | `EDITED` без версии и `DELIVERY_RETRIED` с версией отвергаются (`request_event_version_matches_kind`) |
| `requestEventVersionIsPositive` | `version = 0` отвергается (`request_event_version_positive`) |
| `requestEventVersionIsUniquePerRequest` | два события с одной `(request_id, version)` — второе отвергается (`request_event_request_version_unique`); у разных заявок — принимаются |
| `requestEventChangesAreNonEmptyArray` | `'{}'`, `'[]'` отвергаются (`request_event_changes_array`) |
| `requestEventActorRequired` | `EDITED` и `DELIVERY_RETRIED` без автора отвергаются (`request_event_actor_required`) |
| `requestEventResultHasRecord` | `RESULT_CHANGED` без `inbox_id` отвергается; `EDITED` с `inbox_id` или `problem_id` отвергается (`request_event_result_has_record`) |
| `inboxReceivedIsNotProcessed` | `RECEIVED` с `processed_at` отвергается (`inbox_received_not_processed`); обработанная без `processed_at` принимается (старые строки) |
| `requestEventIndexesExist` | `request_event_request_idx` есть |

**Код:** `V13__request_history.sql` по §2.2 спеки. `TestRows.deleteRequestData` удаляет `request_event` первой.

**Гейт шага:** `./gradlew :dispatch:test --tests '*SchemaMigrationTests'`, затем `./gradlew :dispatch:test`.

## Шаг 3. `inbox.processed_at`

**Тесты (сначала, красные)** — интеграционные, читают строку `inbox`:

| Класс | Тест | Что проверяет |
|---|---|---|
| `InboxProcessorTests` | `processedRecordHasProcessedAt` | `APPLIED`, `STALE`, `CONFLICT`, `INVALID` → `processed_at` = время обработки (часы сдвинуты после получения, значит ≠ `received_at`) |
| `InboxProcessorTests` | `failedRecordHasProcessedAt` | исчерпаны попытки обработки → `FAILED` с `processed_at` |
| `InboxProcessorTests` | `retriedRecordIsNotProcessedYet` | неудачная попытка обработки без исчерпания → `RECEIVED`, `processed_at` пусто |
| `ResultInboxTests` | `receivedRecordIsNotProcessed` | только что полученная запись — `processed_at` пусто |
| `ReconcileAheadTests` | `takenOverRecordHasProcessedAt` | итог из сверки (`EXTERNAL_AHEAD`) → у записи `RECONCILIATION` есть `processed_at` |

**Код:** `InboxDao.recordOutcome` и `markFailed` пишут `processed_at = :now`.

**Гейт шага:** `./gradlew :dispatch:test --tests 'com.acme.dispatch.exchange.*'`.

## Шаг 4. Сборка изменений и запись события

**Тесты (сначала, красные)**

Модульные, `ChangesTest`:

| Тест | Что проверяет |
|---|---|
| `onlyChangedFields` | старые и новые значения по полям → только отличающиеся, в порядке полей |
| `nullsCompare` | `null` → значение и значение → `null` — изменение; `null` → `null` — нет |
| `priceComparesByValue` | `1500` и `1500.00` — не изменение; цена в `changes` строкой без хвостовых нулей |
| `noChangesIsEmpty` | все поля равны → пусто |

Интеграционные, `RequestEventDaoTests`:

| Тест | Что проверяет |
|---|---|
| `insertsEvent` | событие записано со всеми колонками, `changes` читается обратно тем же массивом |
| `daoOnlyInserts` | у `RequestEventDao` нет публичных методов, кроме `insert` (рефлексия) — неизменяемость в коде |

**Код:** `Change`, `Changes`, `RequestEventKind`, `RequestEventDao.insert`.

**Гейт шага:** `./gradlew :dispatch:test --tests 'com.acme.dispatch.history.*'`.

## Шаг 5. `EDITED`

**Тесты (сначала, красные)** — интеграционные, `RequestUpdateTests` (дополнение):

| Тест | Что проверяет |
|---|---|
| `commentChangeIsRecorded` | правка комментария → одно событие `EDITED`: автор, `version` = новой версии заявки, `occurred_at` = `updated_at`, `changes` = `[comment: старый → новый]` |
| `assigneeChangeIsRecorded` | смена ответственного → `[assigneeId: старый → новый]`; снятие → `→ null` |
| `bothFieldsInOneEvent` | комментарий и ответственный одним `PATCH` → одно событие с двумя полями |
| `noopEditIsNotRecorded` | те же значения → `200`, событий нет |
| `forbiddenEditIsNotRecorded` | `403` (инициатор меняет ответственного) → событий нет |
| `staleEditIsNotRecorded` | `409 VERSION_CONFLICT` → событий нет |
| `refusedEventRollsBackEdit` | `RequestEventDao` (spy) бросает → правка откатилась, `version` прежняя |

**Код:** `RequestService.update` запоминает прежние значения до `changeComment` / `changeAssignee`, после `save`
пишет `EDITED` с версией сохранённого агрегата. `RequestEventDao` — Mockito spy в `@IntegrationTest`, как `InboxDao` и
`ProblemDao` (сбрасывается после теста).

**Гейт шага:** `./gradlew :dispatch:test --tests 'com.acme.dispatch.request.*'`.

## Шаг 6. `RESULT_CHANGED` и «нет изменения — нет версии»

**Тесты (сначала, красные)** — интеграционные:

| Класс | Тест | Что проверяет |
|---|---|---|
| `InboxProcessorTests` | `appliedResultIsRecorded` | `IN_PROGRESS`, затем `SUCCEEDED` из ленты → два события `RESULT_CHANGED` без автора, версии 1 и 2, `inbox_id` своих записей, `changes` = `businessStatus AWAITING → IN_PROGRESS`, затем статус, номер, цена |
| `InboxProcessorTests` | `rejectedResultIsRecorded` | `REJECTED` → `businessStatus`, `rejectionReason` |
| `InboxProcessorTests` | `sameResultKeepsVersion` | запись с новым `sequence`, но тем же итогом → `APPLIED`, `last_applied_seq` продвинут, `version` заявки прежняя, события нет |
| `InboxProcessorTests` | `staleAndConflictAreNotRecorded` | `STALE`, `CONFLICT` → событий нет |
| `ReconcileAheadTests` | `takenOverResultIsRecorded` | итог из сверки → `RESULT_CHANGED` с `inbox_id` записи `RECONCILIATION`, без автора |
| `ResolveProblemTests` | `applyCarrierIsRecorded` | `RESULT_CONFLICT` → `APPLY_CARRIER` → `RESULT_CHANGED` с автором-оператором, `problem_id`, `changes` = наш итог → итог перевозчика |
| `ResolveReconciliationProblemTests` | `applyCarrierFromCheckIsRecorded` | то же для `RECONCILIATION_CONFLICT` |
| `ResolveReconciliationProblemTests` | `keepOursIsNotAResultChange` | `KEEP_OURS` → событий `RESULT_CHANGED` нет, `version` прежняя |
| `InboxProcessorTests` | `refusedEventIsNotInvalidData` | при сбое записи события (spy бросает не-`DataAccessException`, как прочие сбои обработки) запись остаётся `RECEIVED` и повторяется, итог заявки не изменён |

**Код**
- `InboxDao.applyToRequest(requestId, message, actor, problemId, inboxId, now)`: `UPDATE … FROM (SELECT … FOR UPDATE)
  old … WHERE (поля) IS DISTINCT FROM (новые) RETURNING old.*, version`; нет строки — нет изменения, события нет.
  Иначе `Changes` из старых и новых значений и `RequestEventDao.insert`.
- `InboxProcessor` передаёт `inbox_id` записи; `OperatorResults` — автора и `problem_id`.
- Существующие тесты, которые ждут рост `version` на том же итоге, — проверить; по спеке он не растёт.

**Гейт шага:** `./gradlew :dispatch:test --tests 'com.acme.dispatch.exchange.*' --tests 'com.acme.dispatch.problem.*'`.

## Шаг 7. `DELIVERY_RETRIED`

**Тесты (сначала, красные)** — интеграционные, `RetryActionTests` (дополнение):

| Тест | Что проверяет |
|---|---|
| `retryFromFailedIsRecorded` | `FAILED` → повтор → событие с автором, без версии, `deliveryStatus FAILED → PENDING` |
| `retryFromUnknownIsRecorded` | `UNKNOWN` → `deliveryStatus UNKNOWN → UNKNOWN` |
| `retryOfLostOperationIsRecorded` | `ACKNOWLEDGED` + `EXTERNAL_MISSING` → `ACKNOWLEDGED → PENDING` |
| `replayedRetryIsNotRecorded` | тот же `Idempotency-Key` второй раз → одно событие |
| `refusedRetryIsNotRecorded` | `409 INVALID_STATE` → событий нет |
| `retryDoesNotChangeVersion` | `version` заявки после повтора прежняя |

**Код:** `SendQueueDao.retryByOperator` возвращает прежний и новый статус (`UPDATE … FROM (SELECT … FOR UPDATE) old
RETURNING`) или пусто; `OperatorActions.retry` пишет `DELIVERY_RETRIED` внутри `idempotency.execute`.

**Гейт шага:** `./gradlew :dispatch:test --tests 'com.acme.dispatch.problem.*' --tests 'com.acme.dispatch.exchange.*'`.

## Шаг 8. Инвариант версий

**Тесты (сначала)** — интеграционный `RequestHistoryInvariantTests`. Должен быть зелёным сразу после шагов 5–7;
красным его делают проверкой: временно убрать запись события в одном из писателей — тест падает с пропуском версии.
Это и есть его проверка, рабочий код не меняется.

| Тест | Что проверяет |
|---|---|
| `versionsHaveNoGaps` | сценарий: правка комментария, смена ответственного, `IN_PROGRESS` и `SUCCEEDED` из ленты, сверка с расхождением, `APPLY_CARRIER`, ещё правка → версии событий `1..delivery_request.version` без пропусков и повторов |
| `replayGivesCurrentState` | состояние при создании + `changes` по порядку версий = текущие комментарий, ответственный, статус, номер, цена, причина |
| `retriesDoNotTakeVersions` | повторы отправки в сценарии — события без версии, на нумерацию не влияют |

**Гейт шага:** `./gradlew :dispatch:test --tests '*RequestHistoryInvariantTests'`.

## Шаг 9. Лента: чтение и права

**Тесты (сначала, красные)**

Модульные, `HistoryRulesTest`:

| Тест | Что проверяет |
|---|---|
| `initiatorSeesBusinessKinds` | инициатору — `CREATED`, `EDITED`, `RESULT_CHANGED`, `DELIVERY_ACKNOWLEDGED`; оператору — все десять |
| `cursorRoundTrip` | курсор из `(at, kind, id)` читается обратно; испорченный — ошибка разбора (`400`) |
| `orderAtSameInstant` | при равном времени — порядок видов по спеке, затем `id` |

Интеграционные, `RequestHistoryTests` (MockMvc, ответы сверяются с контрактом автоматически):

| Тест | Что проверяет |
|---|---|
| `operatorSeesWholeHistory` | заявка с попытками (одна временная ошибка), подтверждением, `IN_PROGRESS` и итогом, сверкой, проблемой и её решением, правкой → все виды в нужном порядке, новые первыми |
| `initiatorSeesBusinessPart` | та же заявка у автора → только деловые виды; у `RESULT_CHANGED` нет `inboxId`/`problemId` |
| `resultReceivedShowsDuplicates` | запись с двумя повторами → одна строка, `duplicates = 2`, время последнего |
| `resultReceivedAtProcessedAt` | `at` = `processed_at`; у старой строки без него — `received_at` |
| `editShowsPeople` | `EDITED` со сменой ответственного → `from`/`to` как `PersonRef` |
| `paging` | `limit = 3` → 3 записи и `nextCursor`; по курсору — следующие без пропусков и повторов; последняя страница — `nextCursor: null` |
| `limitIsChecked` | `limit = 0` и `201` → `400 VALIDATION_FAILED` |
| `foreignRequestIsNotFound` | инициатор чужой заявки, оператор другой службы → `404` |
| `anonymousIsUnauthorized` | без сессии → `401` |

**Код**
- `contracts/dispatch-api.yaml`: путь `/api/requests/{id}/history`, схемы `HistoryPage`, `HistoryEntry` (`oneOf` по
  `kind`), `FieldChange`; примеры. `make contracts-lint`.
- `HistoryRules`, `HistoryQueryDao` (`UNION ALL`: `delivery_request`, `request_event`, `dispatch_operation`,
  `exchange_attempt`, `inbox` по `operation_id`, `reconciliation`, `problem`; фильтр видов по роли в SQL), 
  `HistoryController` (права — `RequestPermissions.canView`, как у карточки).
- `make web-api-types`; web-код не меняется, `pnpm typecheck` зелёный.

**Гейт шага:** `./gradlew build`, `make contracts-lint`, `make web-build`.

## Шаг 10. e2e `dispatch`

**Тесты** — `HistoryE2ETest` (красные на старом образе, зелёные после `make up` с шагами 2–9):

| Тест | Что проверяет |
|---|---|
| `historyFollowsTheRequest` | инициатор создаёт заявку → итог от эмулятора → оператор назначает себя и пишет комментарий → у оператора в ленте `CREATED`, `SEND_ATTEMPT`, `DELIVERY_ACKNOWLEDGED`, `RESULT_RECEIVED`, `RESULT_CHANGED`, `EDITED`; у инициатора — без технических видов |
| `retryIsInHistory` | `loseResponse`, затем `unavailable` → доставка `UNKNOWN` (как в Playwright «неизвестно, получена ли»; исчерпать попытки `unavailable` — ~10 мин) → «Повторить отправку» → `DELIVERY_RETRIED` с автором, `UNKNOWN → UNKNOWN`; режимы выключить → итог. Режимы выключаются до и после теста, как в `ExchangeFaultsE2ETest` |
| `initiatorCannotReadForeignHistory` | инициатор другой заявки → `404` |

Тесты создают свои заявки и проходят повторно на непустой базе.

**Гейт шага:** `make up && make e2e`.

## Шаг 11. web: лента

**Тесты (сначала, красные)**
- `src/requests/card/history/labels.test.ts` (Node): слово и цвет по виду; исход попытки, итог обработки, исход
  сверки, решение — словами; незнакомый вид — его код.
- `src/requests/card/history/changes.test.ts` (Node): `FieldChange` → строка «было → стало» по полю (цена с
  валютой, ответственный по имени, `null` — «не задан», длинный комментарий — признак свёрнутости).
- `src/requests/card/history/RequestHistory.test.tsx` (jsdom, `renderApp`, MSW):

| Тест | Что проверяет |
|---|---|
| оператор | все записи по порядку, «Система» у записей без автора, время в бизнес-зоне |
| фильтр | «Действия людей» — только записи с автором; «Обмен» — попытки, записи, сверки, проблемы, подтверждение |
| инициатор | фильтра нет, технических записей нет |
| «Показать ещё» | второй запрос с `before`, записи добавлены снизу |
| ошибка загрузки | сообщение и «Повторить», карточка работает |
| обновление | смена `card.version` → история перезапрошена |
| клавиатура | «Показать ещё» и раскрытие подробностей с клавиатуры |

**Код:** `requests/card/history/` (`api.ts` — `useInfiniteQuery`, ключ в `requests/keys.ts`; `labels.ts`,
`changes.ts`, `RequestHistory.tsx` на Mantine `Timeline`); раздел «История» в `RequestCardPage`. Обе схемы, 390 px.

**Гейт шага:** `cd web && pnpm vitest run src/requests/card`, затем `make web-build`.

## Шаг 12. Playwright

| Файл | Тест | Что проверяет |
|---|---|---|
| `e2e/requests/card-history.spec.ts` | `the operator sees the whole history` | заявка до итога → оператор назначает себя и правит комментарий → в «Истории» правка «было → стало», итог, попытка; фильтр «Обмен» |
| `e2e/requests/card-history.spec.ts` | `the initiator sees the business part` | у автора — создание, итог, правка оператора; нет попыток и фильтра |
| `e2e/requests/card-phone.spec.ts` | `history on a phone` (дополнение) | 390 px: без горизонтальной прокрутки, подробности раскрываются |

**Гейт шага:** `make up && make web-e2e`.

## Шаг 13. Документы

- README: раздел «История заявки» (что видит оператор и инициатор, `GET …/history`); допущение — журнала
  безопасности нет, входы и отказы пишет технический журнал; ограничения из §5 спеки и неизменяемость только кодом.
- CLAUDE.md: пакет `history`, `request_event`, `inbox.processed_at`, правило «изменение заявки — через двух
  писателей, с событием», `RequestEventDao` как spy, web `requests/card/history/`.
- `docs/PLAN.md` п. 5 — ссылка на план, статус.
- Спека: раздел «Отличия реализации», если они будут.

## Полный гейт

`./gradlew build`, `make contracts-lint`, `make up && make e2e`, `make web-build`, `make web-e2e` — все зелёные.
