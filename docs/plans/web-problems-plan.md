# План: проблемы обмена, действия оператора и сверка во фронтенде

Спека: `docs/specs/web-problems.md` (опрос Q1–Q24, 17.11.2026). Серверные основания: `docs/specs/operator-problems.md`
(a), `docs/specs/reconciliation.md` (b). Внешний вид: `docs/design-system.md`.

Ветка `web-problems` от `operator-problems` ((a) в `main` ещё не влита). Этап A — шаги 1–15, этап B — шаги B1–B4,
начинается после того, как `reconciliation` влита в основу этой ветки.

Работа по TDD: в каждом шаге сначала пишутся тесты шага и запускаются, чтобы увидеть, что они падают **по нужной
причине** (красные), затем код до зелёного, затем рефакторинг. Рабочего кода без падающего теста нет. Один шаг — один
коммит. Гейт шага указан в шаге; в каждом web-шаге, где нет своего e2e, `make web-e2e` всё равно прогоняется как
проверка регрессии. В конце этапа — полный гейт.

## 0. Исходное состояние

**`dispatch`**
- `ProblemController` (`/api/problems`) — только список; `requireOperator(user)` бросает `403`.
- `ProblemQueryDao.find(UUID id, CurrentUser operator)` **уже есть** (область «`p.request_id IS NULL OR
  r.service_id = :serviceId`», используется действиями) — `GET /api/problems/{id}` переиспользует его.
- Некорректный id в пути — `404` (образец: `RequestQueryController.card`, `RequestQueryService.notFound`).
- `RequestQueryDao`: `LIST_COLUMNS` + `FROM` + `where(viewer, query, today, params)`; фильтры — `AND …` в
  `where()`; `mapListItem(rs, today)` собирает `RequestListItem` (record без `openProblems`). `count()` использует
  тот же `where()`.
- `RequestListQuery.parse(businessStatus, deliveryStatus, overdue, page, size, sort)`; `overdue` — `parseBoolean`
  (`true`/`false`, иначе `400`); `RequestQueryController.list` передаёт параметры строками.
- Индекс `problem_request_idx (request_id)` (V9).
- Тесты: `ProblemQueryTests`, `ProblemAccessTests` (там же — вставка и удаление оператора южной службы),
  `RequestAccessTests` (реестр, область), `RequestListQueryTest` (разбор). Ответы MockMvc проверяются по
  `dispatch-api.yaml` автоматически; путь в контракте без реализации сборку не ломает (`ContractCheckTests`
  проверяет обратное).
- Java e2e `ProblemsE2ETest`: `malformedRecordBecomesAProblemAndIsAcknowledged` (вес 12.5 → `SUCCEEDED`, при
  `malformed` цена в ленте испорчена → `INVALID_MESSAGE`), `lostResponseShowsAndClosesDeliveryUnknown`.

**`web`**
- `schema.d.ts` уже содержит `/api/problems`, `resolve`, `retry`, `ExchangeProblem`, `card.problems`/`actions`; в
  `types.ts` псевдонимов для них нет.
- Карточка: `requestCardQueryOptions` — `refetchInterval` выключен при `isFinalCard` (итог и доставка финальны);
  `useUpdateRequest` — образец мутации (отмена опроса, `setQueryData`, инвалидация списков).
- Реестр: `list/search.ts` (`parseListSearch`, `toApiParams(search, role)`, `delivery` отбрасывается у
  инициатора), `RequestFilters`, `RequestTable`, `RequestCards`, `fixtures.ts`.
- `create/idempotency.ts` — ключи по отпечатку содержимого (`keyFor`, `renew`).
- `auth/guards.ts`: `requireRole` → `notFound()`. `AuthenticatedLayout` — шапка без навигации.
- `renderApp(path, { me })`, MSW `src/test/server.ts` (запрос без обработчика валит тест).
- Playwright: `fullyParallel: true`, один проект `chromium`; без `WEB_BASE_URL` — dev-сервер, с ним — стенд;
  `e2e/auth/helpers.ts` (`signInViaApi`), `e2e/requests/helpers.ts` (`uniqueCargo`, `createRequestViaApi`).
  `make web-e2e` прогоняет оба варианта. `CARRIER_BASE_URL` в `Makefile` есть (`:8081`), в Playwright не передаётся.

**Ловушка e2e со сбоями.** Режимы эмулятора глобальны. Пока включён `malformed`, итог **любой** новой заявки
приходит испорченным — параллельные сценарии реестра и карточки, ждущие итога, упадут. Поэтому сценарии со сбоями
идут отдельным проектом Playwright `faults`, который запускается **после** всех остальных (`dependencies:
['chromium']`) и внутри — последовательно: один файл `e2e/problems/faults.spec.ts`, `fullyParallel: false` у
проекта и `test.describe.configure({ mode: 'serial' })`. Основной проект этот файл исключает (`testIgnore`).

## Шаг 1. Ветка и документы дизайна

**Что делаем**
- Ветка `web-problems` от `operator-problems`.
- `docs/dispatch-design.md` §9: фильтр `hasOpenProblems`, поле `openProblems`, `403` инициатору; чтение проблемы
  по id (§7 — одна строка).
- Спека: статус «согласовано», ссылка на этот план.

**Тесты:** нет. **Готово, когда:** дизайн и спека не противоречат друг другу. Коммит `docs: …`.

## Шаг 2. `GET /api/problems/{id}`

**Тесты (сначала, красные)**
- Интеграционные, `ProblemQueryTests` (красные: `404`/`405` от Spring на неизвестный путь):
  - по каждому виду (`DELIVERY_UNKNOWN`, `DELIVERY_FAILED`, `INVALID_MESSAGE` с заявкой и без, `MESSAGE_FAILED`,
    `RESULT_CONFLICT`) ответ по id равен элементу списка с тем же id (сравнение JSON целиком);
  - закрытая проблема — `resolvedBy`, `decision`, `comment`, `actions = []`.
- Интеграционные, `ProblemAccessTests`:
  - инициатор — `403 FORBIDDEN`;
  - оператор южной службы на проблему северной — `404 NOT_FOUND`;
  - проблема без заявки — `200` обоим операторам;
  - несуществующий UUID и `not-a-uuid` — `404`.
- Контракт: путь отсутствует — валидатор ответов MockMvc валит тесты (ожидаемо красные до правки контракта).

**Код**
- `contracts/dispatch-api.yaml`: `/api/problems/{id}` `get` (`operationId: getProblem`, параметр `id` строкой как у
  `resolve`), ответы `200 ExchangeProblem`, `401`, `403`, `404`; `make contracts-lint`, `make web-api-types`.
- `ProblemController.get(String id, user)`: `requireOperator`, разбор UUID (ошибка → `404`), `queries.find(id, user)`
  или `404`; `@Transactional(readOnly = true)`.

**Гейт шага:** `./gradlew :dispatch:test`, `make contracts-lint`, `cd web && pnpm api:check`.

## Шаг 3. Реестр: `hasOpenProblems` и `openProblems`

**Тесты (сначала, красные)**
- Модульные, `RequestListQueryTest`: `hasOpenProblems` — `true`, `false`, пусто → `null`, `yes` → `400` с именем
  параметра.
- Интеграционные, новый `RequestListProblemsTests` (`@IntegrationTest`, заявки через `TestRequests`, проблемы через
  настоящие переходы: `FakeCarrier` + `SendWorker.runOnce()` для `DELIVERY_FAILED`/`UNKNOWN`, `TestResults.deliver`
  с испорченной записью для `INVALID_MESSAGE`):
  - `hasOpenProblems=true` — только заявки с открытой проблемой; заявка, чья проблема закрыта, не попадает;
    `false` — только без открытых; `totalElements` совпадает с выборкой;
  - вместе с `deliveryStatus` и `overdue` — пересечение;
  - `openProblems`: 0, 1, 2 (открыты `DELIVERY_UNKNOWN` и `DELIVERY_FAILED`); закрытая не считается;
  - инициатор: `openProblems = null` в каждой строке; с `hasOpenProblems` (любое значение) — `403 FORBIDDEN`.
- Контракт: новое поле и параметр — валидатор ответов красный до правки.

**Код**
- Контракт: параметр `hasOpenProblems` (`boolean`), ответ `403` у `GET /api/requests`; `RequestListItem.openProblems`
  (`[integer, 'null']`, в `required`). `make contracts-lint`, `make web-api-types`.
- `RequestListQuery`: поле `Boolean hasOpenProblems`, `parse(…)` с новым аргументом (`parseBoolean`).
- `RequestQueryController.list`: параметр; `RequestQueryService.list`: инициатор с непустым `hasOpenProblems` →
  `ApiException.forbidden`.
- `RequestQueryDao`: условие `EXISTS` / `NOT EXISTS` в `where()`; у оператора в `findPage` колонка
  `(SELECT count(*) FROM problem p WHERE p.request_id = r.id AND p.status = 'OPEN') AS open_problems`, у
  инициатора — `NULL::int`; `mapListItem` читает её как `Integer`.
- `RequestListItem`: поле `Integer openProblems`.
- **web компилируется**: `openProblems: null`/число в `requests/list/fixtures.ts` и в обработчиках MSW тестов
  реестра (тип стал обязательным). Поведение web не меняется.

**Гейт шага:** `./gradlew :dispatch:test`, `make contracts-lint`, `make web-build`.

## Шаг 4. Java e2e

**Тесты** (`ProblemsE2ETest`, дополнение сценария `malformed`). Красными их видно, запустив `make e2e` против
стенда, поднятого **до** шагов 2–3 (старый образ: `404` на `{id}`, нет `openProblems`); затем `make up` пересобирает
образ, и они зелёные. Если стенд уже пересобран — красный прогон пропускается, это отмечается в коммите.
- проблема из списка читается по `{id}`: тот же JSON;
- реестр оператора с `hasOpenProblems=true` содержит заявку, `openProblems = 1`; после «Разобрано» — заявки нет,
  `openProblems = 0` без фильтра;
- инициатор: `403` на `/api/problems/{id}` и на `/api/requests?hasOpenProblems=true`.

**Код:** нет (только тест). **Гейт шага:** `./gradlew build`, `make up && make e2e`.

## Шаг 5. Общий помощник `Idempotency-Key`

**Тесты (сначала, красные)**
- Модульные, `src/shared/actions/idempotency.test.ts`: тот же отпечаток — тот же ключ; другой — новый; `forget` —
  следующий `keyFor` даёт новый; `renew` — то же для одного отпечатка; ключи двух экземпляров независимы.
- Существующие `create/idempotency.test.ts` и `CreateRequestPage.test.tsx` — без изменений, должны остаться зелёными
  после перевода.

**Код**
- `src/shared/actions/idempotency.ts`: `createActionKeys<T>(fingerprint: (t: T) => string, newKey?)` →
  `{ keyFor, renew, forget }`; `useActionKeys(fingerprint)`.
- `create/idempotency.ts` — тонкая обёртка над ним (отпечаток содержимого формы); её тест переезжает в общий или
  остаётся как тест обёртки.

**Гейт шага:** `make web-build`.

## Шаг 6. Модуль `src/problems/`: слова, данные, `ProblemDetails`

**Тесты (сначала, красные)**
- Модульные, `problems/labels.test.ts`: для каждого значения `ProblemKind` и `ProblemDecision` из `schema.d.ts`
  (список берётся из типа через `satisfies Record<…>`) — слово, пояснение, цвет; неизвестный `kind` → код как есть и
  `gray`.
- Компонентные, `problems/details/ProblemDetails.test.tsx` (рендер в `AppProviders`, без маршрутизации; фикстуры
  `problems/fixtures.ts` по каждому виду):
  - открытая: название, пояснение, причина, время в бизнес-зоне, ссылка на заявку, `operationId` с копированием;
    без заявки — «—», без ссылки;
  - `INVALID_MESSAGE`/`MESSAGE_FAILED`: только непустые поля `message`, начало текста моноширинно, «Копировать»;
  - `RESULT_CONFLICT`: две колонки, различающиеся поля помечены словом «отличается» (не только цветом);
  - закрытая: «Закрыта 17.11, 14:05 · Иванов · Оставлен наш итог», комментарий; `SYSTEM` — без автора;
  - кнопки решений — только из `actions`, `onDecide(decision)` вызывается по нажатию.

**Код**
- `types.ts`: `ExchangeProblem`, `ExchangeProblemPage`, `ProblemKind`, `ProblemDecision`, `ProblemMessage`,
  `CurrentResult`, `RequestAction`, `ResolveProblem`.
- `problems/keys.ts`, `labels.ts`, `fixtures.ts`, `details/` (`ProblemDetails`, `CarrierMessage`, `ConflictView`,
  `ResolvedLine`), `index.ts`. Новые цвета/отступы — в `tokens.ts`.

**Гейт шага:** `make web-build`.

## Шаг 7. Блок «Проблемы» в карточке и опрос

**Тесты (сначала, красные)**
- Модульные, `requests/card/api.test.ts`: `refetchInterval` — финальная без проблем → `false`; финальная с открытой
  проблемой → 5000; неитоговая → 5000; `problems: null` (инициатор) → по-старому.
- Компонентные, `ProblemsBlock.test.tsx` (`renderApp('/requests/<id>')`, MSW): открытые плашками в порядке ответа,
  цвет по виду; закрытые свёрнуты «Закрытые проблемы (2)», раскрываются; пустой `problems` — блока нет; инициатор —
  блока нет.
- Компонентные, `phone.test.tsx` (дополнение): на телефоне блок сразу под шапкой карточки.
- E2E (Playwright, проект `faults`, §0): инфраструктура — помощник `e2e/problems/carrier.ts` (`setFaults`,
  `allOff`; `CARRIER_BASE_URL`, по умолчанию `http://localhost:8081`; недоступен → `test.skip` с причиной), проект
  `faults` в `playwright.config.ts` с `dependencies: ['chromium']`, `testMatch: 'problems/faults.spec.ts'`,
  основной проект его исключает. Сценарий: `loseResponse` + `delayed`, заявка через API под инициатором, оператор
  в карточке видит плашку «Неизвестно, получена ли заявка», затем она в «Закрытых» с «Закрыта системой» (ожидание до
  90 с, как у Java e2e).

**Код**
- `requests/card/api.ts`: `hasOpenProblem(card)`, `refetchInterval` с ним.
- `problems/ProblemsBlock.tsx` (через `index.ts`), место в `RequestCardPage` (обе раскладки).
- `Makefile` `web-e2e`: передать `CARRIER_BASE_URL`.

**Гейт шага:** `make web-build`, `make up && make web-e2e`.

## Шаг 8. «Повторить отправку»

**Тесты (сначала, красные)** — `RetryAction.test.tsx` (`renderApp`, MSW):
- кнопки нет без `RETRY`; есть в панели «Обмен» и в плашке `DELIVERY_FAILED`;
- текст окна для `FAILED` и для `UNKNOWN`;
- `200`: запрос с `Idempotency-Key`, карточка из ответа на экране (статус «В очереди»), уведомление, окно закрыто;
- обрыв (MSW `HttpResponse.error()`): сообщение «повтор безопасен», окно открыто, повтор уходит с **тем же** ключом;
  `503` — так же;
- `409 INVALID_STATE`: сообщение, карточка перезапрошена (второй `GET`), «Понятно» закрывает;
- `404`: «Заявка не найдена»; `403`: сообщение о правах; `422`: следующий запрос — с новым ключом;
- новое окно после успеха — новый ключ.
- Модульные, `problems/resolve/errors.test.ts` (общая таблица §8.5 для повтора; для решения дополняется в шаге 9).

**Код**
- `card/api.ts`: `useRetryDelivery(id)` (как `useUpdateRequest`: отмена опроса, `setQueryData`, инвалидация
  `requestKeys.lists`, `problemKeys.all`).
- `card/RetryAction.tsx` (кнопка + `Modal`), `problems/resolve/errors.ts` (ответ → `{ message, refetch, gone }`).

**E2E:** не воспроизводится на стенде (спека §10.4) — только компонентные. **Гейт шага:** `make web-build`.

## Шаг 9. Решение по проблеме

**Тесты (сначала, красные)**
- Модульные, `resolveSchema.test.ts`: пустой, из пробелов → «Опишите основание решения»; 2000 — да; 2001 →
  «Не больше 2000 символов». `errors.test.ts` — строки решения.
- Компонентные, `ResolveModal.test.tsx` (в карточке через `renderApp`):
  - заголовок и текст по решению; блок конфликта в окне для `RESULT_CONFLICT`;
  - пустой комментарий не уходит, ошибка под полем;
  - `200`: тело `{decision, comment}`, ключ в заголовке, окно закрыто, уведомление, перезапрошены карточка, список
    проблем, счётчик, реестр (проверка по вызовам MSW или `invalidateQueries`);
  - обрыв → тот же ключ, комментарий сохранён; комментарий изменён → новый ключ;
  - `409` → сообщение, перезапрос, «Понятно»; `400 VALIDATION_FAILED` → ошибка поля; `404` → «Проблема недоступна».
- Компонентные, `conflict.page.test.tsx` (дополнение): после `APPLY_CARRIER` (версия в ответе карточки +1) правка
  комментария со старой версией получает `409 VERSION_CONFLICT` и тихо переотправляется.
- E2E (`faults`): `malformed`, заявка, оператор в карточке видит «Некорректное сообщение перевозчика», «Разобрано»
  с комментарием → в «Закрытых» с автором и комментарием.

**Код:** `problems/api.ts` `useResolveProblem`, `resolve/ResolveModal.tsx`, `resolveSchema.ts`; кнопки решений
`ProblemDetails` открывают окно.

**Гейт шага:** `make web-build`, `make web-e2e`.

## Шаг 10. Список проблем `/problems`

**Тесты (сначала, красные)**
- Модульные, `problems/list/search.test.ts`: умолчания не пишутся; `status`, несколько `kind`; мусор и неизвестный
  `kind` отброшены; страница с 1; `toApiParams`.
- Компонентные, `ProblemListPage.test.tsx`: строки из ответа; `SegmentedControl` и `MultiSelect` пишут адрес и
  уходят в запрос, страница сбрасывается; пагинация; пустые состояния (умолчание / фильтр); обрыв — сообщение и
  «Повторить», старые строки на месте; на телефоне — карточки и шторка фильтров.
- Компонентные, `problems/routes.test.ts`: инициатор на `/problems` — «Не найдено».
- E2E (обычный проект): права — инициатор на `/problems` видит «Не найдено»; оператор — заголовок и пустое или
  непустое состояние без ошибок консоли; 390 px — карточки без горизонтальной прокрутки.

**Код:** `problems/list/*`, `problems/routes.tsx`, регистрация в `app/router.tsx`.

**Гейт шага:** `make web-build`, `make web-e2e`.

## Шаг 11. Панель проблемы `/problems/$id`

**Тесты (сначала, красные)**
- Компонентные, `ProblemDrawer.test.tsx`:
  - клик по строке → `/problems/<id>?status=…` (параметры списка сохранены), `GET /api/problems/<id>`;
  - прямая ссылка на проблему не с текущей страницы — открыта;
  - `404` и `403` — «Проблема не найдена или недоступна», список виден; обрыв — «Повторить»;
  - решение в панели → панель показывает закрытую проблему без кнопок, список перезапрошен;
  - «Закрыть» и «Назад» → `/problems` с прежними параметрами;
  - опрос раз в 5 с, пока открыта; закрытая не опрашивается (поддельные таймеры).
- Компонентные, `problems/routes.test.ts`: инициатор на `/problems/<id>` — «Не найдено».
- E2E (`faults`): `malformed`, оператор открывает `/problems`, строку проблемы этой заявки, видит начало текста,
  «Разобрано» с комментарием → в панели итог; «Закрытые» содержат её с автором. 390 px — панель во весь экран, окно
  решения помещается.

**Код:** дочерний маршрут `/problems/$id` под маршрутом списка (компонент списка рендерит `<Outlet />`),
`panel/ProblemDrawer.tsx`, `problemQueryOptions(id)`.

**Гейт шага:** `make web-build`, `make web-e2e`.

## Шаг 12. Навигация и счётчик в шапке

**Тесты (сначала, красные)** — `AuthenticatedLayout.test.tsx` (дополнение):
- оператор: ссылки «Заявки» и «Проблемы», активная выделена; бейдж с `totalElements`; 0 — без бейджа; `503` — без
  бейджа и без уведомлений;
- инициатор: навигации нет, запроса счётчика нет (MSW без обработчика — тест упал бы);
- телефон: иконки с `aria-label`, бейдж на иконке проблем;
- после решения (шаг 9) счётчик перезапрошен.
- E2E (`faults`, сценарий шага 11 дополняется): до решения бейдж ≥ 1, после — уменьшился.

**Код:** `problems/nav/OpenProblemsNav.tsx`, `openCountQueryOptions` (`refetchInterval: 30_000`,
`refetchIntervalInBackground: false`; повторы на ошибке — общие, только для транзиентных; ошибка не показывается).
Слот навигации: `RouterContext` (`app/rootRoute.tsx`) получает поле `headerNav: ComponentType` (для оператора;
компонент сам ничего не рисует у инициатора), `createAppRouter` кладёт туда `OpenProblemsNav`,
`AuthenticatedLayout` берёт его из `useRouteContext`. Так `auth` не импортирует `problems`. `renderApp` получает
то же через `createApp`.
- E2E (обычный проект): инициатор — навигации в шапке нет.

**Гейт шага:** `make web-build`, `make web-e2e`.

## Шаг 13. Реестр: фильтр и значок

**Тесты (сначала, красные)**
- Модульные, `requests/list/search.test.ts`: `problems=1` у оператора → `hasOpenProblems: true`; у инициатора
  отброшен; смена сбрасывает страницу; «Сбросить» убирает.
- Компонентные, `RequestFilters.test.tsx` / `RequestListPage.test.tsx`: переключатель только у оператора; запрос с
  `hasOpenProblems=true`; значок с числом и `aria-label` «Открытых проблем: 2» в таблице и в карточках телефона;
  `0`/`null` — без значка; пустое состояние «Заявок с открытыми проблемами нет».
- E2E (`faults`, сценарий шага 11 дополняется): до решения заявка в реестре с «Есть открытые проблемы» и значком,
  после — её нет. Обычный проект: у инициатора переключателя нет.

**Код:** `list/search.ts`, `RequestFilters`, `RequestTable`, `RequestCards`, `RequestListPage` (пустое состояние).

**Гейт шага:** `make web-build`, `make web-e2e`.

## Шаг 14. Просмотр и доступность

**Что делаем:** обе схемы, 1280 и 390 px — список, панель, карточка с каждым видом проблемы, окно решения. Данные —
настоящие, со стенда: `make fault-malformed`, `make fault-lose-response` + `make fault-delayed` (`DELIVERY_FAILED` и
конфликт — по `docs/specs/demo-seed.md`, если он готов, иначе только компонентными тестами); клавиатура: строка списка открывается
`Enter`, окно решения — фокус на комментарии, `Esc` закрывает, кнопки достижимы `Tab`. Найденное правится с тестом.

**Тесты:** компонентный — `Enter` на строке открывает панель; фокус в окне на поле комментария.
**Гейт шага:** `make web-build`, `make web-e2e`.

## Шаг 15. Документы и полный гейт этапа A

- `CLAUDE.md`: модуль `src/problems/`, `GET /api/problems/{id}`, `hasOpenProblems`/`openProblems`, проект Playwright
  `faults`; README — раздел для оператора; `docs/manual-testing.md` — проверка экранов (`make fault-malformed`,
  `fault-lose-response` + `fault-delayed`, `DELIVERY_FAILED` и конфликт — по `docs/specs/demo-seed.md`).
- Спека: статус «реализовано», раздел «Отличия реализации от спеки», если есть.
- Полный гейт: `./gradlew build`, `make up && make e2e`, `make contracts-lint`, `make web-build`, `make web-e2e`.

## Этап B (после влития `reconciliation`)

### Шаг B1. Типы и новые виды

- `make web-api-types`; псевдонимы `Reconciliation`; тестовые данные: `actions` с `RECONCILE`, `reconciliations`.
- **Тесты:** `labels.test.ts` — новые виды (тест по `satisfies Record` сам станет красным при появлении значений);
  `ProblemDetails.test.tsx` — `RECONCILIATION_CONFLICT` с итогом перевозчика и без («У перевозчика итога ещё нет»),
  `EXTERNAL_MISSING` с итогом и без; `RetryAction.test.tsx` — текст для `ACKNOWLEDGED` + `EXTERNAL_MISSING`.
- **Гейт:** `make web-build`.

### Шаг B2. «Сверить сейчас»

- **Тесты:** `ReconcileAction.test.tsx` — кнопка только при `RECONCILE`; без окна; ключ новый на каждое нажатие,
  тот же после обрыва; исходы словом и цветом, `acknowledged`; `ERROR` — обычный ответ; карточка перезапрошена;
  `404`/`403`/`422` по §8.5.
- **Код:** `card/ReconcileAction.tsx`, `useReconcile(id)`.
- **Гейт:** `make web-build`.

### Шаг B3. История сверок

- **Тесты:** `ReconciliationHistory.test.tsx` — до 20 строк, новые первыми; запуск словами, «вручную · Иванов»;
  раскрытие с `details` и итогом перевозчика; у инициатора нет.
- **Гейт:** `make web-build`.

### Шаг B4. E2E и документы

- Playwright (обычный проект): заявка с итогом, «Сверить сейчас» → «Совпадает», проверка в истории.
- `CLAUDE.md`, README, `manual-testing.md`; полный гейт.

## Риски

| Риск | Что делаем |
|---|---|
| Глобальные режимы эмулятора ломают параллельные сценарии | проект `faults` после основного, последовательно, выключение до и после каждого теста (§0) |
| `make web-e2e` гонит сценарии дважды (dev и стенд), состояние стенда растёт | каждый сценарий создаёт свою заявку с уникальным грузом и ищет только её проблемы (`kind` + номер заявки) |
| Дочерний маршрут с `Drawer` теряет параметры списка | навигация в панель с `search: true`, закрытие — `to: '/problems', search: true`; тест шага 11 |
| `openProblems` подзапросом замедлит реестр (NF08) | на 20 строк по индексу `problem_request_idx`; проверка — нагрузочный прогон п. 8 плана, индекс — тогда |
| `APPLY_CARRIER` и черновик правки в карточке | существующий разбор `VERSION_CONFLICT`, тест шага 9 |
| Тип `openProblems` обязателен — ломает компиляцию web на шаге 3 | фикстуры правятся в том же шаге, гейт шага — `make web-build` |

## Не входит

Как в спеке §11: история обмена и лента событий (п. 5 плана), уведомления и назначение проблем, сбои эмулятора из UI.
