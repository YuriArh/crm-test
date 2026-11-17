# План: расхождение остаётся видно после решения

Основание: спека `docs/specs/conflict-history.md` (согласована 17.11.2026, Q1–Q3 приняты как предложено). Ветка
`conflict-history` от `main` (после влития `demo-seed`). Работа по TDD: в каждом шаге сначала пишутся тесты шага и
запускаются, чтобы увидеть, что они падают **по нужной причине** (красные), затем код до зелёного, затем
рефакторинг. Рабочего кода без падающего теста нет. Один шаг — один коммит; после каждого шага — гейт шага, в
конце — полный гейт.

## 0. Исходное состояние

- Миграции V1–V11. `SchemaMigrationTests.allMigrationsAreApplied` — список до `11`.
- `reconciliation.local_result` — ключ `ResultMessage.content(status, tracking, price, reason)`
  (`status|tracking|price|reason`, цена через `stripTrailingZeros`), пусто без итога. Пишет его
  `ReconciliationDao.insert` из `Row.localResult()`; источник — `state.localResult()` в `Reconciler` (стр. 151–196).
  Структурных полей нашей стороны нет.
- `ProblemDao`:
  - `open(id, kind, requestId, inboxId, details, now)` — `INSERT … VALUES … ON CONFLICT DO NOTHING`. Его вызывают
    `ProblemService` для доставки (стр. 47), для записи ленты, в том числе `RESULT_CONFLICT` (стр. 72; `requestId`
    может быть пуст у записи неизвестной заявки) и для `EXTERNAL_MISSING` (стр. 91);
  - `openReconciliationConflict(...)` и `relinkOpenReconciliationConflict(...)`;
  - `lastReconciliationConflict` читает `r.local_result` для правила «решено ранее» — не меняется.
- `ProblemQueryDao.map`: `message` из `inbox` или из строки сверки, `currentResult` из `delivery_request` для трёх
  видов. Снимка нет.
- `ReconciliationQueryDao` не читает нашу сторону; `ReconciliationView` её не содержит.
- Контракт: `Reconciliation` без нашей стороны, `ExchangeProblem` без `ourSide`. Ответы `@IntegrationTest`
  проверяются по контракту автоматически: новое поле в ответе без контракта ломает тесты, поэтому контракт
  меняется в том же шаге, что и чтение (шаг 5).
- web: `ConflictView` сравнивает `message` с `currentResult`; `OurResult` показывает `currentResult`;
  `ReconciliationHistory.CheckDetails` показывает только перевозчика. Фикстуры — `problems/fixtures.ts`,
  `requests/card/testData.ts`.
- e2e (`e2e/`) не умеют создать расхождение: у эмулятора нет команды, меняющей итог. В `make seed` (§8
  `demo-seed.md`) это делается правкой базы эмулятора через `psql`. PostgreSQL открыт на `DB_PORT` (5432).

## Шаг 1. Документы дизайна

**Что делаем**
- `docs/dispatch-design.md` §4: новые колонки `reconciliation.local_*` и `problem.our_*`, их ограничения, правило
  «снимок в том же `INSERT`/`UPDATE`, после закрытия не меняется».
- `docs/specs/reconciliation.md` §2.9–2.10, `docs/specs/web-problems.md` §7.1, §8.3 — ссылки на новую спеку и что
  она меняет.
- Спека: статус «согласовано», ссылка на этот план.

**Тесты:** нет. **Готово, когда:** дизайн и спеки не противоречат друг другу.

## Шаг 2. Схема V12

**Тесты (сначала, красные)** — интеграционные, `SchemaMigrationTests`:

| Тест | Что проверяет |
|---|---|
| `allMigrationsAreApplied` | список до `12` |
| `reconciliationLocalStatusIsChecked` | `local_status = 'DONE'` отвергается (`reconciliation_local_status_valid`) |
| `reconciliationLocalResultMustBeComplete` | `SUCCEEDED` без номера или цены, `REJECTED` без причины, поля без статуса — отвергаются (`reconciliation_local_result_complete`) |
| `problemOurStatusIsChecked` | `our_status = 'DONE'` отвергается |
| `problemOurResultMustBeComplete` | то же правило полноты для `our_*` |
| `problemOurSideNeedsRecord` | `our_recorded = false` и непустое `our_status` отвергается |
| `problemOurSideMatchesKind` | `our_recorded = true` у `DELIVERY_FAILED` / `INVALID_MESSAGE` отвергается; у трёх видов принимается |
| `ourRecordedHasNoDefault` | `INSERT` без `our_recorded` отвергается (`NOT NULL`, default снят) |

Заполнение старых строк проверяется отдельным тестом на чистой схеме `V11`: Flyway с `target = 11` в отдельной
схеме, вставка строк, затем миграция до `12`:

| Тест | Что проверяет |
|---|---|
| `v12FillsLocalSideFromKey` | `SUCCEEDED\|TRK-00000001\|1500\|` → `local_*`; `REJECTED\|\|\|Адрес \| не обслуживается` → причина с `\|` целиком; пусто → все `local_*` пусты |
| `v12FillsReconciliationConflictFromItsRow` | `RECONCILIATION_CONFLICT` → `our_recorded = true`, `our_*` из `local_*` её строки |
| `v12LeavesOtherConflictsUnrecorded` | `RESULT_CONFLICT`, `EXTERNAL_MISSING` → `our_recorded = false`, `our_*` пусты |

**Код:** `V12__conflict_sides.sql` по §2.2 спеки: колонки, заполнение, `SET NOT NULL`, `DROP DEFAULT`,
ограничения добавляются после заполнения.

**Гейт шага:** `./gradlew :dispatch:test --tests '*SchemaMigrationTests'`, затем `./gradlew :dispatch:test`.

## Шаг 3. Строка сверки хранит нашу сторону

**Тесты (сначала, красные)** — интеграционные, читают строку `reconciliation` из базы:

| Класс | Тест | Что проверяет |
|---|---|---|
| `ReconcileConflictTests` | `conflictRowKeepsBothSides` | итоги разные → в строке `carrier_*` перевозчика и `local_*` заявки, они различаются |
| `ReconcileConflictTests` | `onlyOursRowKeepsOurSide` | итог только у нас → `local_*` заполнены, `carrier_result_status` пуст |
| `ReconcilerTests` | `matchRowKeepsEqualSides` | `MATCH` с итогом → `local_*` = `carrier_*` |
| `ReconcilerTests` | `rowWithoutOurResultHasEmptyLocalSide` | итога у заявки нет → `local_*` пусты, `local_result` пуст |
| `ReconcileAheadTests` | `aheadRowKeepsOurSideBeforeTakeover` | `EXTERNAL_AHEAD` → `local_*` пусты (итога у нас до сверки не было), итог заявки уже перевозчика |

**Код:** `Reconciler` передаёт в `ReconciliationDao.Row` наш итог структурно (`LocalResult(status, tracking,
price, reason)` или `null`). Ключ `local_result` строится из него же через `ResultMessage.content`, чтобы ключ и
поля не могли разойтись. `ReconciliationDao.insert` пишет `local_*`.

**Гейт шага:** `./gradlew :dispatch:test --tests 'com.acme.dispatch.exchange.reconcile.*'`.

## Шаг 4. Проблема хранит снимок нашего итога

**Тесты (сначала, красные)** — интеграционные, читают строку `problem`:

| Класс | Тест | Что проверяет |
|---|---|---|
| `MessageProblemsTests` | `resultConflictRecordsOurResult` | `RESULT_CONFLICT` открыта → `our_recorded = true`, `our_*` = итог заявки до спорной записи |
| `MessageProblemsTests` | `invalidRecordHasNoOurSide` | `INVALID_MESSAGE` (в том числе запись неизвестной заявки, `request_id` пуст) → `our_recorded = false` |
| `DeliveryProblemsTests` | `deliveryProblemsHaveNoOurSide` | `DELIVERY_UNKNOWN` / `DELIVERY_FAILED` → `our_recorded = false` |
| `ReconcileDeliveryTests` | `externalMissingRecordsOurResult` | `EXTERNAL_MISSING` с итогом → снимок итога; без итога → `our_recorded = true`, `our_*` пусты |
| `ReconcileConflictTests` | `conflictRecordsOurResult` | `RECONCILIATION_CONFLICT` открыта → `our_*` = `local_*` её строки |
| `ReconcileConflictTests` | `relinkUpdatesOurSideWithRow` | повторная сверка при открытой проблеме → `reconciliation_id` и `our_*` из новой строки |
| `ResolveProblemTests` | `applyCarrierKeepsSnapshot` | `RESULT_CONFLICT` → `APPLY_CARRIER`: итог заявки = записи перевозчика, `our_*` — прежний итог |
| `ResolveReconciliationProblemTests` | `snapshotFrozenAfterResolve` | `APPLY_CARRIER` и `KEEP_OURS` → `our_*` не меняются; новая сверка после `KEEP_OURS` («решено ранее») закрытую проблему не трогает |

**Код:** `ProblemDao.open`, `openReconciliationConflict`, `relinkOpenReconciliationConflict` копируют итог из
`delivery_request` в том же операторе (`INSERT … SELECT … FROM (VALUES …) LEFT JOIN delivery_request`, чтобы запись
неизвестной заявки тоже открывалась; `UPDATE … FROM delivery_request`). Нужен ли снимок, решает
`ProblemKind.hasTwoSides()` (`RESULT_CONFLICT`, `RECONCILIATION_CONFLICT`, `EXTERNAL_MISSING`). Модульный тест
`ProblemKindTest.twoSidedKinds` — сначала красный.

Для `RECONCILIATION_CONFLICT` итог заявки и `local_*` строки совпадают: оба читаются под блокировкой строки
операции. Тест `conflictRecordsOurResult` это проверяет.

**Гейт шага:** `./gradlew :dispatch:test --tests 'com.acme.dispatch.problem.*' --tests
'com.acme.dispatch.exchange.*'`.

## Шаг 5. API: `localResult` и `ourSide`

**Тесты (сначала, красные)** — интеграционные через MockMvc (ответы сверяются с контрактом автоматически):

| Класс | Тест | Что проверяет |
|---|---|---|
| `ResolveReconciliationProblemTests` | `cardShowsBothSidesAfterApplyCarrier` | карточка после `APPLY_CARRIER`: `problems[0].ourSide.result` — прежний итог, `currentResult` — перевозчика, `message` — перевозчика; `reconciliations[0].localResult` ≠ `carrierResult` |
| `ResolveProblemTests` | `problemShowsOurSideAfterApplyCarrier` | `GET /api/problems/{id}` у `RESULT_CONFLICT` после решения — то же |
| `ProblemQueryTests` | `ourSideNullWhenNotRecorded` | `our_recorded = false` (строка вставлена напрямую) → `ourSide: null`; `DELIVERY_FAILED` → `ourSide: null` |
| `ProblemQueryTests` | `ourSideWithoutResult` | `EXTERNAL_MISSING` без итога → `ourSide: {result: null}` |
| `ReconcileActionTests` | `manualCheckReturnsLocalResult` | ответ «Сверить сейчас» содержит `localResult` |

**Код**
- `contracts/dispatch-api.yaml`: схема `ResultSnapshot`, `Reconciliation.localResult`, `ExchangeProblem.ourSide`
  (+ `OurSide`), примеры. `make contracts-lint`.
- `ReconciliationView.localResult`, `ReconciliationQueryDao` читает `local_*`.
- `ExchangeProblem.ourSide`, `ProblemQueryDao` читает `our_*`.
- `make web-api-types`. После этого web-фикстуры (`problems/fixtures.ts`, `requests/card/testData.ts`) получают
  новые поля со значением по умолчанию (`ourSide: null`, `localResult: null`), иначе `pnpm typecheck` красный.
  Поведение web в этом шаге не меняется.

**Гейт шага:** `./gradlew build`, `make contracts-lint`, `make web-build`.

## Шаг 6. e2e `dispatch`: расхождение видно после решения

Расхождение у настоящего эмулятора делается так же, как в `make seed` §8: правкой его базы («перевозчик изменил
данные у себя»). Для этого в `e2e/` появляется помощник `CarrierDatabase`: JDBC к базе `carrier`, адрес и учётка из
`CARRIER_DB_URL` / `CARRIER_DB_USER` / `CARRIER_DB_PASSWORD` (по умолчанию `localhost:${DB_PORT}`, `carrier`/
`carrier`). У помощника один метод — `changeResult(operationId, tracking, price)`, это тот же `UPDATE operation`,
что в §8.1. Зависимость `org.postgresql:postgresql` (`testImplementation`, версия из BOM). Переменные передаются в
`make e2e`. Классы `carrier-emulator` не используются: `dispatch` и эмулятор по-прежнему не делят код.

**Тесты (красные до шагов 2–5 на стенде; пишутся первыми в этом шаге и падают на старом образе)**

| Класс | Тест | Что проверяет |
|---|---|---|
| `ReconciliationE2ETest` | `appliedConflictKeepsBothSides` | заявка выполнена → `changeResult` → «Сверить сейчас» → `RECONCILIATION_CONFLICT` → `APPLY_CARRIER` с комментарием → карточка: `currentResult` = новый номер, `problems[0].ourSide.result` = прежний, у строки `CONFLICT` `localResult` ≠ `carrierResult` |
| `ReconciliationE2ETest` | `keptConflictKeepsBothSides` | то же с `KEEP_OURS`: `currentResult` = `ourSide.result`, `message` отличается |

Тесты создают свои заявки и проходят повторно на непустой базе.

**Гейт шага:** `make up && make e2e`.

## Шаг 7. web: общая таблица сравнения

**Тесты (сначала, красные)**
- `src/problems/details/comparison.test.ts` (Node): `compareResults(carrier, ours)` → строки (статус, номер,
  стоимость, причина) с признаком «отличается»; пустые с обеих сторон строки отбрасываются; `differences(...)` →
  названия отличающихся полей в порядке строк; одинаковые итоги → пусто; итога нет с одной стороны → отличается
  статус.
- `src/problems/details/ResultComparison.test.tsx` (jsdom, `renderInRouter`): две колонки с заголовками из
  параметров, «отличается» только у отличающихся строк, «Итога нет» у пустой стороны.

**Код:** `comparison.ts` (чистая логика, принимает `ResultSnapshot | null` с обеих сторон), `ResultComparison.tsx`.
Строки «Наш итог» из `OurResult.ourResultRows` переходят в `comparison.ts`.

**Гейт шага:** `cd web && pnpm vitest run src/problems/details`.

## Шаг 8. web: блок конфликта и `EXTERNAL_MISSING`

**Тесты (сначала, красные)** — `ProblemDetails.test.tsx` (дополнение):

| Тест | Что проверяет |
|---|---|
| открытый `RECONCILIATION_CONFLICT` | колонки «Версия перевозчика» / «Наш итог при обнаружении», «отличается» у номера и стоимости |
| закрытый `APPLY_CARRIER` | колонки различаются (из `message` и `ourSide`), строка исхода «Принята версия перевозчика», «Итог сейчас» с итогом перевозчика |
| закрытый `KEEP_OURS` | «Оставлен наш итог», «Итог сейчас» = наш |
| закрытый системой | «Закрыта системой» |
| `ourSide = null` | правая колонка — `currentResult` с пометкой «итог сейчас; снимок на момент проблемы не сохранён», без «отличается» |
| `EXTERNAL_MISSING` | «Наш итог при обнаружении» из `ourSide`; при отличии `currentResult` — «Итог сейчас» ниже |

**Код:** `ConflictView` на `ResultComparison` (снимок вместо `currentResult`), блок исхода закрытой проблемы,
`OurResult` берёт `ourSide`. Проверка обеих схем и 390 px.

**Гейт шага:** `cd web && pnpm vitest run src/problems`.

## Шаг 9. web: «Сверки»

**Тесты (сначала, красные)** — `ReconciliationHistory.test.tsx` (дополнение):

| Тест | Что проверяет |
|---|---|
| строка `CONFLICT` | в заголовке «Отличается: номер отправления, стоимость» без раскрытия |
| раскрытая `CONFLICT` | колонки «У перевозчика» / «У нас», отметки «отличается» |
| `MATCH` | без «Отличается» в заголовке, без отметок в раскрытой строке |
| `ERROR` | колонка перевозчика — «Нет ответа», наша сторона показана |

**Код:** `CheckDetails` на `ResultComparison`; строка отличий в `Accordion.Control` только у `CONFLICT`.

**Гейт шага:** `cd web && pnpm vitest run src/requests/card`, затем `make web-build`.

## Шаг 10. Playwright

Помощник `web/e2e/problems/carrierDb.ts`: тот же `UPDATE operation`, что в шаге 6. Чтобы не тянуть драйвер
PostgreSQL в web, он выполняется через `docker compose exec -T postgres psql -U carrier -d carrier` (как в
`make seed`). Тест живёт в проекте `faults` (`faults.spec.ts`): он меняет данные перевозчика, а этот проект идёт
последним и по одному тесту.

| Тест | Что проверяет |
|---|---|
| `a resolved reconciliation conflict still shows what differed` | заявка выполнена → правка у перевозчика → «Сверить сейчас» → «Принять версию перевозчика» с комментарием → в плашке колонки различаются, «Принята версия перевозчика», «Итог сейчас»; в «Сверках» у строки `CONFLICT` «Отличается: номер отправления, стоимость» |

**Гейт шага:** `make up && make web-e2e`.

## Шаг 11. Документы

- CLAUDE.md: `problem.our_*`, `reconciliation.local_*`, `ResultComparison`, e2e-помощники `CarrierDatabase` /
  `carrierDb.ts`, переменные `CARRIER_DB_*`.
- `docs/specs/demo-seed.md` §8.1–8.3: ожидаемое после решения (колонки различаются, строка исхода, «Итог сейчас»).
- Спека: раздел «Отличия реализации», если они будут.

## Полный гейт

`./gradlew build`, `make contracts-lint`, `make up && make e2e`, `make web-build`, `make web-e2e` — все зелёные.
