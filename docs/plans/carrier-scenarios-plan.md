# План: сценарии перевозчика для демо — изменить итог, забыть операцию

Основание: спека `docs/specs/carrier-scenarios.md`. Ветка `carrier-scenarios` от `main`. Работа по TDD: в каждом
шаге сначала тесты шага, запуск — падают **по нужной причине** (красные), затем код до зелёного, затем
рефакторинг. Один шаг — один коммит; гейт шага указан в шаге, в конце — полный гейт.

## 0. Исходное состояние

- Эмулятор: `admin/AdminOperationsController` (`GET /admin/operations`), `operation/OperationDao.find(id)` отдаёт
  `AdminOperation`; ошибки — `web/CarrierException(ErrorCode, message)` → `{code, message}` (`web/ErrorHandler`);
  UUIDv7 — `config` эмулятора. Исполнитель: `ExecutionDao.markExecuted` (`… WHERE state = 'ACCEPTED'`),
  `clearPublishAt` (`… AND result_publish_at IS NOT NULL`) — условные, пропавшую строку пропускают.
- Тесты эмулятора: `@IntegrationTest`, `MutableClock`, исполнитель выключен (`Executor.runOnce()` / `publishDue()`),
  `TestRows.resetState` в `@AfterEach` у тестов, трогающих режимы и часы; `support/Operations` — отправка операций;
  `support/Feed` — чтение ленты; каждый ответ MockMvc — `Contract.matchesContract()`.
- e2e: `e2e/CarrierClient` (режимы, `adminOperation`, `feedEnd`), `ReconciliationE2ETest` (3 теста).
- `scripts/seed.sh`: `scenario <key> "<modes>" <point> <what> <cargo> <weight> <address> <days> [after]`,
  `reconcile_now` с ключом `seed-north-8-reconcile`.

## Шаг 1. Документы и ветка

Ветка `carrier-scenarios` от `main`; спека и этот план — docs-коммитом.

## Шаг 2. Контракт

- `contracts/carrier-api.yaml`: `PUT /admin/operations/{operationId}/result` (тело `AdminResultChange`; `200`
  `AdminOperation`, `400`, `404`, `409`), `DELETE /admin/operations/{operationId}` (`204`, `404`), код
  `OPERATION_NOT_SETTLED` в `Error.code`, оговорка в правиле «Reconciliation» описания (спека §3.3). Примеры к
  ответам.
- **Проверка:** `make contracts-lint`; `./gradlew :carrier-emulator:test :dispatch:test` — зелёные (код не
  меняется; `dispatch` контракт эмулятора не валидирует по путям `admin`).

Тесты ручек — минимальные (спека §6, решение пользователя 18.11.2026): основной путь каждой ручки и отказ `409`.

## Шаг 3. `PUT …/result`

**Тесты (красные)** — `admin/AdminScenariosTests` (новый, `@IntegrationTest`, `Contract.matchesContract()` на каждый
ответ):

| Тест | Ожидание |
|---|---|
| `changesTheResultOfAnExecutedOperation` | операция исполнена (`runOnce()`) → другой номер и цена: `200`; `GET /operations/{id}` — новый итог с новым `messageId` |
| `operationNotExecutedYetIsNotSettled` | `ACCEPTED` — `409 OPERATION_NOT_SETTLED` |

Запуск до кода: падают на отсутствии эндпоинта, не на контракте.

**Код:** константа `OPERATION_NOT_SETTLED(HttpStatus.CONFLICT)` в `web/ErrorCode` (единственная правка
существующего файла, спека §2); `admin/AdminResultChange` (тело, проверка правил итога — `@Valid` + проверка
сочетания полей, ошибка как у остальных `400`); `admin/ScenarioDao.changeResult(id, change, messageId)` — один
условный `UPDATE … WHERE operation_id = :id AND state = 'EXECUTED' AND result_publish_at IS NULL`, `0` строк →
`OperationDao.find` различает `404` и `409`; `admin/AdminScenariosController` — ответ через `OperationDao.find`,
`WARN Scenario changeResult: …`.

**Гейт:** `./gradlew :carrier-emulator:test`.

## Шаг 4. `DELETE /admin/operations/{id}`

**Тест (красный)** — в `AdminScenariosTests`: `forgetsAnExecutedOperation` — `204`, затем `GET /operations/{id}` —
`404`. Запуск до кода: падает на отсутствии метода.

**Код:** `ScenarioDao.forget(id)` — в одной транзакции `SELECT … FOR UPDATE` строки (нет — `404`), удаление
записей ленты, удаление строки; число удалённых записей — для лога; контроллер — `204`,
`WARN Scenario forget: …`.

**Гейт:** `./gradlew :carrier-emulator:test`.

## Шаг 5. Java e2e: сценарии со сверкой `dispatch`

**Тесты (красные)** — `e2e/CarrierClient`: `changeResult(operationId, status, trackingNumber, price, reason)`,
`forget(operationId)`; `ReconciliationE2ETest` (режимы выключаются до и после, как сейчас):

| Тест | Ожидание |
|---|---|
| `changedCarrierResultIsAReconciliationConflict` | заявка с итогом → `changeResult` → `reconcile` — `CONFLICT`, открыта `RECONCILIATION_CONFLICT` |
| `forgottenOperationWithoutAResultCanBeRetried` | `delayed` → `ACKNOWLEDGED` → режимы выключены → `forget` (до публикации) → `reconcile` — `EXTERNAL_MISSING`, в `actions` карточки `RETRY` |

Запуск против стенда, собранного до шага 3 (`make up` на `main`): падают на отсутствии эндпоинтов эмулятора.

**Код:** нет (эмулятор готов в шагах 3–4). **Гейт:** `make up && make e2e`.

Playwright-сценариев нет: кнопки решений по этим проблемам уже проверены тестами `docs/specs/web-problems.md`.

## Шаг 6. `make seed`: `seed-north-9…11`

**Ожидание (красное сейчас):** чистый стенд, `make seed` — открытая проблема одна (`seed-north-8`).

**Код** (`scripts/seed.sh`): `change_result <id>`, `forget <id>` (`curl` к `$CARRIER/admin/...`, ошибка → выход с
сообщением); `reconcile_now` принимает ключ (`seed-north-N-reconcile`); сценарии:
- `seed-north-9`: без режимов, точка — итог; `after`: `change_result` (другой номер, цена 999,00) → `reconcile_now`;
- `seed-north-10`: без режимов, точка — итог; `after`: `forget` → `reconcile_now`;
- `seed-north-11`: `delayed`, точка — `ACKNOWLEDGED`; `after`: `forget` → `reconcile_now`.
Для них ожидание после `after` — не итог, а открытая проблема: `scenario` получает условие конца (по умолчанию —
итог). Ожидаемый `outcome` сверки — `CONFLICT` / `EXTERNAL_MISSING`; другое — предупреждение, как у `seed-north-8`.

**Проверка:** `bash -n`, ShellCheck в Docker (как в `docs/plans/demo-seed-plan.md`); чистый стенд `make reset &&
make up && make seed`:
- 14 заявок; открыты `INVALID_MESSAGE` (8), `RECONCILIATION_CONFLICT` (9), `EXTERNAL_MISSING` (10, есть «Оставить
  наш итог»), `EXTERNAL_MISSING` (11, в карточке `RETRY`); счётчик шапки 4; в реестре с «Есть открытые проблемы» —
  эти 4;
- режимы выключены; повторный `make seed` — сценарии пропущены, `PUT`/`DELETE` не вызывались (в логе эмулятора нет
  новых `Scenario …`).

## Шаг 7. Документы

- `docs/manual-testing.md` §8: 8.1, 8.3, 8.4 — `curl` к эндпоинтам вместо `psql`, без оговорки о простаивающем
  стенде; 8.2 — без изменений; новый пункт «после `make seed` — разберите четыре проблемы». Пройти 8.1, 8.3, 8.4 на
  стенде по тексту.
- `docs/carrier-emulator-design.md` §7 (два эндпоинта), §8 (исключение «Записи не удаляются»).
- `docs/specs/demo-seed.md`: Q4/Q21 пересмотрены — ссылка на эту спеку; таблица §2.1 — три заявки.
- README: «Эмулятор перевозчика» (эндпоинты сценариев), «Демо-данные» (+3 заявки, 4 открытые проблемы).
- `CLAUDE.md`: пакет `admin` эмулятора (`AdminScenariosController`, `ScenarioDao`), `make seed`.
- Спека: статус «реализовано», отличия — отдельным разделом, если есть.

## Шаг 8. Полный гейт

`./gradlew build`, `make up && make e2e`, `make contracts-lint`; `make reset && make up && make seed` — проверка
шага 6. `web/` не меняется, поэтому `make web-build` и `make web-e2e` в гейт не входят.

## Риски

| Риск | Что делаем |
|---|---|
| Удаление операции во время её исполнения | блокировка строки первой (`FOR UPDATE`), затем лента, затем строка; отдельного теста гонки нет (решение 18.11.2026: тесты ручек минимальные) |
| `seed-north-11`: публикация отложенного итога (15 с) раньше `forget` | `forget` сразу после `ACKNOWLEDGED` (секунды); если итог всё же пришёл — сверка даст не `EXTERNAL_MISSING`, сид напечатает предупреждение с последним состоянием |
| Новый код ошибки в общем перечислении контракта | `dispatch` читает `code` строкой и `admin` не вызывает; шаг 2 гоняет тесты `dispatch` |
| e2e оставляют на стенде открытые проблемы | они создают свои заявки с уникальным грузом; сид от них не зависит |
