# Обмен с внешней системой

Тестовое задание: `04-system-integration.md`. План работ: `docs/PLAN.md`.

## Структура

```
dispatch/          основное приложение: принимает заявки на доставку, передаёт их
                   перевозчику, отслеживает доставку и результат, сверяет
carrier-emulator/  эмулятор внешнего перевозчика (своя база `carrier`)
contracts/         OpenAPI-контракты (dispatch-api.yaml: веб ↔ dispatch, carrier-api.yaml: dispatch ↔ перевозчик)
docker/            скрипт инициализации PostgreSQL (создаёт базу эмулятора)
web/               веб-клиент: React + TypeScript + Vite + Mantine, в Docker раздаётся nginx
docs/              план и документация
```

## Что нужно

- Docker с Docker Compose
- JDK 25 — только чтобы собирать и запускать `dispatch` вне Docker
- Node.js 22.12+ с corepack (pnpm; его включает `make web-install`) — только для разработки `web/` вне Docker

## Запуск

```bash
cp .env.example .env
make up        # собрать и запустить postgres + dispatch + carrier-emulator + web
make ps        # состояние сервисов
```

Проверка: `curl http://localhost:8080/actuator/health`, `curl http://localhost:8080/api/system/info` и `curl http://localhost:8081/actuator/health` (эмулятор).

Веб-клиент открывается на http://localhost:3000. Он и API работают на одном origin: nginx раздаёт собранную статику и проксирует `/api` в `dispatch`.

Если порт 5432, 8080, 8081 или 3000 занят, поменяйте `DB_PORT` / `DISPATCH_PORT` / `CARRIER_PORT` / `WEB_PORT` в `.env`. Его читают и `docker compose`, и `make run`.

База эмулятора создаётся скриптом из `docker/postgres/init` только на пустом томе PostgreSQL. Если стенд запускался ещё до появления эмулятора, один раз выполните `make reset` (он удаляет локальные данные).

## Демо-пользователи

Пароль у всех: `demo1234`.

| Логин | Роль | Служба |
|---|---|---|
| `north.initiator` | инициатор | Север |
| `north.operator` | оператор | Север |
| `south.initiator` | инициатор | Юг |

Оператор видит только заявки службы «Север», каждый инициатор — только свои.

## API

| Метод и путь | Кому | Что делает |
|---|---|---|
| `GET /api/auth/csrf` | всем | ставит cookie `XSRF-TOKEN` и возвращает токен |
| `POST /api/auth/login` | всем | `{username, password}` → cookie сессии + текущий пользователь |
| `POST /api/auth/logout` | вошедшим | завершает сессию (204) |
| `GET /api/auth/me` | вошедшим | текущий пользователь и служба |
| `GET /api/users/operators` | оператору | операторы своей службы (выбор ответственного) |
| `POST /api/requests` | инициатору | создать заявку; заголовок `Idempotency-Key` обязателен; заявка сразу ставится в очередь к перевозчику |
| `GET /api/requests` | вошедшим | реестр: `businessStatus`, `deliveryStatus`, `overdue`, `page`, `size` (≤ 100), `sort` (`createdAt` / `deadline` / `number`, `,asc` / `,desc`) |
| `GET /api/requests/{id}` | вошедшим | карточка заявки; оператор получает ещё блок `exchange`, проблемы заявки `problems` и доступные действия `actions` |
| `PATCH /api/requests/{id}` | автору / оператору | `{version, comment?, assigneeId?}`; не переданное поле сохраняется, `null` очищает; ответственного назначает только оператор |
| `GET /api/problems` | оператору | проблемы обмена своей службы и записи без заявки: `status` (`OPEN` по умолчанию, `RESOLVED`, `ALL`), `kind` (можно несколько), `page`, `size` |
| `POST /api/requests/{id}/retry` | оператору | повторить отправку при доставке `FAILED` или `UNKNOWN`: новый бюджет попыток, отправка сейчас, `operationId` прежний; `Idempotency-Key` обязателен |
| `POST /api/problems/{id}/resolve` | оператору | `{decision, comment}`: `APPLY_CARRIER` / `KEEP_OURS` для конфликта результатов, `ACKNOWLEDGED` («Разобрано») для плохой записи; комментарий обязателен; `Idempotency-Key` обязателен |

- Каждый изменяющий запрос требует заголовок `X-XSRF-TOKEN` со значением cookie `XSRF-TOKEN`. После входа токен меняется, поэтому cookie нужно перечитать.
- Ошибки — Problem Details (RFC 9457) с машиночитаемым `code`: `VALIDATION_FAILED` (с `errors[]` по полям), `IDEMPOTENCY_KEY_REQUIRED`, `IDEMPOTENCY_KEY_REUSED`, `UNAUTHENTICATED`, `BAD_CREDENTIALS`, `FORBIDDEN`, `CSRF_INVALID` (CSRF-токена нет или он устарел: получите новый и повторите), `NOT_FOUND`, `VERSION_CONFLICT` (с текущим состоянием в `current`), `INVALID_STATE` (действие недоступно в текущем состоянии), `INVALID_ASSIGNEE`. У остальных HTTP-ошибок код — имя статуса, например `METHOD_NOT_ALLOWED`, `UNSUPPORTED_MEDIA_TYPE`.
- Повторное создание с тем же `Idempotency-Key` возвращает первый ответ с заголовком `Idempotent-Replayed: true`.

### Как заявка доходит до результата

1. `POST /api/requests` в одной транзакции сохраняет заявку и ставит её операцию в очередь на отправку: `deliveryStatus: PENDING`, `businessStatus: AWAITING`.
2. Воркер отправки (примерно раз в секунду) отправляет её перевозчику с неизменяемым `operationId` → `ACKNOWLEDGED`. Если перевозчик недоступен или отвечает `5xx` → `RETRY_WAIT`; ответа не дождались → `UNKNOWN`. В обоих случаях отправка повторяется с тем же `operationId` (пауза от 5 с, удваивается до 5 минут, не больше 8 попыток, затем `FAILED`). `4xx` → `FAILED`.
3. Опрос ленты (примерно раз в секунду) читает ленту результатов перевозчика и передаёт каждую запись единому обработчику: карточка переходит в `IN_PROGRESS`, затем в `SUCCEEDED` (номер отправления, стоимость) или `REJECTED` (причина). Дубликаты и устаревшие результаты ни на что не влияют, итоговый результат никогда не перезаписывается.
4. Результат заодно подтверждает доставку: заявка, застрявшая в `UNKNOWN`, становится `ACKNOWLEDGED` и повторно не отправляется.

Состояние обмена оператор видит в блоке `exchange` карточки: попытки, следующая попытка, последняя ошибка, когда и как подтверждена доставка. Каждый результат меняет `version` заявки, поэтому перед правкой перечитайте карточку.

### Проблемы и действия оператора

То, что требует человека, становится **проблемой** (подробно — `docs/specs/operator-problems.md`):

| Проблема | Когда | Кто закрывает |
|---|---|---|
| `DELIVERY_UNKNOWN` | ответ на отправку потерян: дошла ли заявка — неизвестно | система, когда перевозчик подтвердит получение (ответом или результатом) |
| `DELIVERY_FAILED` | попытки кончились или перевозчик отверг запрос | система при подтверждении; оператор — «Повторить» |
| `INVALID_MESSAGE` | перевозчик прислал запись, нарушающую контракт | оператор — «Разобрано» с комментарием |
| `MESSAGE_FAILED` | `dispatch` не смог обработать запись | оператор — «Разобрано» с комментарием |
| `RESULT_CONFLICT` | запись расходится с уже применённым результатом | оператор — «принять перевозчика» (только итоговую запись) или «оставить наш», с комментарием |

Проблема открывается и закрывается в той же транзакции, что и вызвавший её переход. Решение оператора (кто, когда, что, комментарий) хранится в самой проблеме. «Повторить» не сбрасывает номер попытки: он только растёт, а лимит и паузы отсчитываются заново от момента повтора.

Допущения:

- «Принять перевозчика» — единственный способ изменить итоговый результат после того, как он стал итоговым; автоматически итог не меняется никогда.
- Плохую запись повторно не разбираем: итог такой заявки подтянет сверка (`docs/specs/reconciliation.md`), оператор закрывает проблему «Разобрано».
- Проблемы записей, в которых не удалось прочитать известный `operationId`, не привязаны к заявке, и их видят операторы всех служб.
- `409 PAYLOAD_MISMATCH` от перевозчика означает ошибку у нас (содержимое заявки неизменяемо) и становится `DELIVERY_FAILED`.

### Пример с curl

```bash
JAR=$(mktemp)                       # хранилище cookie: сессия + CSRF
API=http://localhost:8080
token() { awk '$6 == "XSRF-TOKEN" { print $7 }' "$JAR"; }

curl -s -c "$JAR" -b "$JAR" $API/api/auth/csrf > /dev/null
curl -s -c "$JAR" -b "$JAR" -H "X-XSRF-TOKEN: $(token)" -H 'Content-Type: application/json' \
     -d '{"username":"north.initiator","password":"demo1234"}' $API/api/auth/login

# Создать (срок не раньше сегодняшнего дня по Europe/Moscow)
curl -s -c "$JAR" -b "$JAR" -H "X-XSRF-TOKEN: $(token)" -H 'Content-Type: application/json' \
     -H "Idempotency-Key: $(uuidgen)" \
     -d '{"cargoDescription":"Pallets","weightKg":12.5,"deliveryAddress":"Main st 1","deadline":"2026-12-01"}' \
     $API/api/requests

curl -s -b "$JAR" "$API/api/requests?businessStatus=SUCCEEDED"   # через секунду-две: перевозчик исполнил

# Правка: id и текущая версия берутся из карточки
curl -s -c "$JAR" -b "$JAR" -X PATCH -H "X-XSRF-TOKEN: $(token)" -H 'Content-Type: application/json' \
     -d '{"version":<version>,"comment":"Fragile"}' $API/api/requests/<id>
```

Действия оператора (войдите как `north.operator` тем же способом):

```bash
curl -s -b "$JAR" "$API/api/problems?kind=INVALID_MESSAGE&kind=RESULT_CONFLICT"

# Повторить отправку заявки в FAILED или UNKNOWN
curl -s -c "$JAR" -b "$JAR" -X POST -H "X-XSRF-TOKEN: $(token)" -H "Idempotency-Key: $(uuidgen)" \
     $API/api/requests/<id>/retry

# Решить проблему: APPLY_CARRIER / KEEP_OURS для конфликта, ACKNOWLEDGED для плохой записи
curl -s -c "$JAR" -b "$JAR" -H "X-XSRF-TOKEN: $(token)" -H 'Content-Type: application/json' \
     -H "Idempotency-Key: $(uuidgen)" -d '{"decision":"ACKNOWLEDGED","comment":"Сообщил перевозчику"}' \
     $API/api/problems/<problemId>/resolve
```

## Эмулятор перевозчика

`carrier-emulator` (порт 8081) изображает внешнего перевозчика. Контракт и правила обмена: `contracts/carrier-api.yaml`; устройство: `docs/carrier-emulator-design.md`; режимы сбоев: `docs/specs/carrier-faults.md`.

| Метод и путь | Что делает |
|---|---|
| `POST /operations` | принять операцию; `202` и на повтор с тем же содержимым (без второго исполнения), `409 PAYLOAD_MISMATCH` на тот же `operationId` с другим содержимым |
| `GET /operations/{operationId}` | состояние у перевозчика и последний результат (для сверки); `404`, если операция неизвестна |
| `GET /results?after=N&limit=100` | лента результатов после курсора `N`: `IN_PROGRESS`, затем `SUCCEEDED` / `REJECTED` |
| `GET /admin/operations[?operationId=]` | операции с `receivedCount`, `executionCount`, запомненными `faults` и `resultPublishAt` (то же `make carrier-ops`) |
| `GET` / `PUT /admin/faults` | выключатели режимов сбоев (то же `make faults`, `make fault-*`) |

- Операции исполняются в фоне примерно через секунду после приёма.
- Исход детерминирован: вес больше 10 000 кг или `тест-отказ` в адресе → отказ; иначе номер отправления `TRK-…` и стоимость `300 + 25 × вес`.
- Допущение: аутентификации нет — эмулятор это локальный тестовый стенд внутри сети Docker.

```bash
ID=$(uuidgen)
curl -s -H 'Content-Type: application/json' \
     -d "{\"operationId\":\"$ID\",\"cargoDescription\":\"Pallets\",\"weightKg\":12.5,\"deliveryAddress\":\"Main st 1\",\"deadline\":\"2026-12-01\"}" \
     localhost:8081/operations
sleep 2
curl -s localhost:8081/operations/$ID
curl -s "localhost:8081/results?after=0"
```

### Режимы сбоев

Четыре выключателя в памяти эмулятора (после его перезапуска все выключены):
- `unavailable` — `503` на эндпоинтах обмена;
- `loseResponse` — ответ на первый приём держится, пока операция не исполнена и не прошло 10 с, то есть дольше таймаута `dispatch` (5 с);
- `duplicate` — каждая запись ленты публикуется дважды с одним `messageId`;
- `delayed` — операция исполняется сразу, а записи ленты публикуются через 15 с (на стенде), итог раньше `IN_PROGRESS`.

`duplicate` и `delayed` запоминаются операцией в момент приёма. Каждая `make fault-*` включает только свой режим, сочетания задаются через `PUT /admin/faults`. Задержки: `CARRIER_LOSE_RESPONSE_DELAY`, `CARRIER_DELAYED_RESULT_DELAY`.

```bash
make fault-lose-response   # создать заявку → итог по ленте, подтверждение через RESULT, повтора нет
make carrier-ops           # одна операция, receivedCount = 1, state = EXECUTED
curl -X PUT localhost:8081/admin/faults -H 'Content-Type: application/json' \
  -d '{"unavailable":false,"loseResponse":true,"duplicate":false,"delayed":true,"malformed":false}'
                           # создать заявку → UNKNOWN, повтор, ACKNOWLEDGED через RESPONSE, итог через ~15 с
make carrier-ops           # одна операция, receivedCount = 2, state = EXECUTED
make fault-duplicate       # создать заявку → итог один, в ленте по две записи на сообщение
make fault-delayed         # создать заявку → итог через ~15 с, запоздавший IN_PROGRESS игнорируется
make fault-malformed       # создать заявку → «В работе», испорченный итог отсеян; после make fault-off новые заявки проходят
make fault-unavailable     # создать заявку → RETRY_WAIT; затем make fault-off → итог
make fault-off
make faults                # текущие выключатели
```

Куда смотреть: карточка `dispatch` под оператором (`deliveryStatus`, `businessStatus`, `exchange.attempts`, `exchange.acknowledgedVia`, `exchange.lastError`), `make carrier-ops` (ровно одна операция на заявку, `receivedCount`, `faults`, `resultPublishAt`) и лог эмулятора (`make logs-carrier`, строки `Fault <mode>: …`).

### Падение и перезапуск во время обмена

Подробные ручные тесты с падением `dispatch` и БД в разные моменты обмена: `docs/manual-testing.md`.

Особого режима для перезапуска нет. Когда бы `dispatch` или база ни упали и сколько бы ни лежали, после старта каждая принятая заявка доходит до результата, ничего не теряется и ничего не исполняется дважды (D04, D-NF01, NF03). Конкретные моменты (посреди отправки, до чтения ленты, истёкший захват) покрыты интеграционными тестами (`SendWorkerTests`, `FeedPollerTests`), а на стенде это проверяется так, как бывает в жизни.

```bash
make dispatch-kill     # SIGKILL: dispatch умирает посреди того, что делал
make dispatch-start    # запустить снова и дождаться health
make db-kill           # то же для PostgreSQL
make db-start
```

1. Создайте заявки в интерфейсе: несколько сразу, с любыми режимами сбоев (`make fault-*`) или без них.
2. В любой момент выполните `make dispatch-kill`. Подождите сколько хотите, затем `make dispatch-start` и войдите заново: сессии хранятся в памяти.
3. Ожидаемо:
   - каждая заявка дошла до результата (реестр и карточка);
   - у каждой операции `executionCount = 1` в `make carrier-ops`;
   - все заявки, созданные до падения, на месте.

Замечания:
- После выключения сбоя заявка ждёт своей «Следующей попытки» (паузы 5, 10, 20, 40… с), а опрос ленты после недоступности перевозчика может сделать паузу до 30 с.
- Если держать `unavailable` включённым дольше ~10 минут, 8 попыток кончатся и заявка перейдёт в `FAILED`. Это ожидаемо: дальше решает оператор.
- `make db-kill` / `make db-start` не перезапускают `dispatch`, поэтому сессия сохраняется. PostgreSQL общий, так что эмулятор тоже теряет базу и восстанавливается сам. Пока база лежит, интерфейс показывает ошибки загрузки. `make db-start` ждёт, пока оба приложения снова станут здоровы, это может занять около минуты.

## Тесты

```bash
make test      # unit- и интеграционные тесты (интеграционные используют Testcontainers: нужен запущенный Docker)
make up        # поднять стенд
make e2e       # сквозные тесты по настоящему HTTP против запущенного стенда
make contracts-lint   # проверить contracts/ (после изменения контракта)
```

e2e-тесты создают свои данные и могут запускаться повторно на непустой базе.

## Разработка

```bash
make db        # только PostgreSQL
make run       # dispatch локально (./gradlew :dispatch:bootRun)
make test      # тесты; интеграционные используют Testcontainers, поэтому нужен запущенный Docker
```

`make help` показывает все команды.

## Соглашения

- Моменты времени хранятся и обрабатываются в UTC; бизнес-часовой пояс — `APP_TIMEZONE` (по умолчанию `Europe/Moscow`).
- Схемой базы управляют только миграции Flyway (`dispatch/src/main/resources/db/migration`).
- Доступ к данным: Spring Data JDBC для сущностей, явный SQL через `JdbcClient` для ядра обмена; без JPA/Hibernate.

## Веб-клиент

```bash
make web-install   # один раз: зависимости web/ и браузер для Playwright
make web-dev       # http://localhost:5173, проксирует /api в dispatch на :8080 (make run или make up)
make web-build     # проверка типов по контракту, typecheck, unit-тесты, production-сборка
make web-e2e       # Playwright: против dev-сервера, затем против стенда на :3000 (сначала make up)
```

- Стек: React 19, TypeScript (strict), Vite, TanStack Router, TanStack Query, Mantine; pnpm-проект вне Gradle.
- Дизайн-система в духе Linear (по умолчанию тёмная, светлая схема — переключателем в шапке): токены, тема Mantine и правила в `docs/design-system.md`.
- Модульная структура: `src/app/` связывает приложение (query client, роутер, тема), в `src/shared/` код, общий для нескольких модулей, у каждого функционального модуля своя папка (и своя `e2e/<module>/`).
- Слой данных `src/shared/api/`: типы, сгенерированные из `contracts/dispatch-api.yaml`, один HTTP-клиент с CSRF-заголовком и ошибками Problem Details. После изменения контракта выполните `make web-api-types`; `make web-build` падает, пока типы устарели.
- Вход: откройте http://localhost:3000 и войдите демо-пользователем (см. выше; они перечислены и на странице входа). Любая страница без сессии ведёт на страницу входа, а после входа обратно. В шапке пользователь, роль, служба и кнопка выхода. Если сессия потеряна (выход в другой вкладке, перезапуск `dispatch`), следующее действие ведёт на страницу входа.
- Заявки: после входа открывается реестр (`/requests`). Фильтры «Результат», «Доставка» (для операторов) и «Только просроченные», сортировка и страница хранятся в адресе, поэтому ссылка или «Назад» их восстанавливают. На телефоне таблица превращается в карточки, а фильтры переезжают в нижнюю шторку. Список обновляется сам каждые 10 с.
- Инициатор создаёт заявку кнопкой «Новая заявка»: форма проверяет поля, показывает «Проверьте данные» и только потом отправляет. Повторный клик или повтор после потерянного ответа создают одну заявку. Срок не может быть раньше сегодняшнего дня в бизнес-часовом поясе.
- Карточка заявки показывает содержимое (только чтение — после создания оно не меняется), оба статуса, результат перевозчика и, для операторов, детали обмена. Она обновляется каждые 5 с, пока результат не итоговый. Комментарий правит любой, кто видит заявку, ответственного выбирает оператор. Если кто-то другой тем временем изменил то же поле, ничего не перезаписывается молча.
- `make web-e2e` гоняет браузерные тесты против стенда в том виде, в каком он собран: после изменений в `web/` сначала пересоберите его через `make up` (или `docker compose up -d --build web`).
