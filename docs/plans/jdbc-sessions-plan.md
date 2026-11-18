# План: сессии входа в PostgreSQL (Spring Session JDBC)

Основание: спека `docs/specs/jdbc-sessions.md` (опрос Q1–Q16). Нагрузка NF08 сделана (`docs/load-report.md`), условие
Q1 выполнено. Ветка `jdbc-sessions` от `main`. Работа по TDD: в каждом шаге сначала пишутся тесты шага и
запускаются, чтобы увидеть, что они падают **по нужной причине** (красные), затем код до зелёного, затем рефакторинг.
Рабочего кода без падающего теста нет. Один шаг — один коммит; после каждого шага проходит гейт шага.

## 0. Исходное состояние

- **Перед веткой.** В `main` не закоммичены документы этой сессии: переписанный `docs/manual-testing.md`, абзац о
  сессиях в памяти в `README.md` и `docs/dispatch-design.md` §2, спека `docs/specs/jdbc-sessions.md`. Они коммитятся в
  `main` отдельным коммитом до создания ветки (с согласия пользователя), иначе ветка их не увидит.
- Сессия — `HttpSession` Tomcat в памяти. `SecurityConfig`: `DelegatingSecurityContextRepository`
  (`RequestAttribute…` + `HttpSession…`), CSRF в cookie (`CookieCsrfTokenRepository`), `/error` в `PUBLIC_ENDPOINTS`.
  `AuthController`: `ChangeSessionIdAuthenticationStrategy` при входе, `SecurityContextLogoutHandler` при выходе.
- `CurrentUser` — record, `UserDetails` (`Serializable`); в сессии хранится `withoutPassword()`.
- Своего обработчика `/error` нет: ошибка вне контроллеров отвечает JSON Spring Boot (`timestamp`, `status`, `error`,
  `path`), без `code`.
- **Коды ошибок фреймворка уже по правилу:** `ApiExceptionHandler.createResponseEntity` ставит в `code` имя
  HTTP-статуса (`METHOD_NOT_ALLOWED`, `BAD_REQUEST`, …) через `statusCodeName`, а контракт (`Problem.code`) это
  описывает: «… or the HTTP status name for framework errors». Поэтому решение Q14 спеки («новый код
  `BAD_REQUEST` в `ErrorCode`») заменяется существующим правилом: прочие `4xx` → имя статуса. `ErrorCode` и перечень
  кодов в контракте не меняются (шаг 1 правит спеку).
- Тесты: `AuthTests` переносит сессию через `MockHttpSession` (`loginSession`, `meWorksWithTheLoginSession`,
  `sessionStoresPrincipalWithoutPasswordHash`, `logoutEndsTheSession`, `csrfEndpointTokenIsAcceptedByLogin`).
  `csrf()` из Spring Security Test подменяет репозиторий CSRF на сессионный — после перехода любой тест с `csrf()`
  создаёт строку `spring_session`. Это безвредно (строки истекают), проверки сессий в шаге 4 ищут строку по id своей
  сессии, а не по количеству, поэтому общей очистки таблицы не нужно.
- `SchemaMigrationTests` ждёт версии `1`–`13`. Следующая миграция — `V14`.
- Факты, проверенные для плана: стартер `org.springframework.boot:spring-boot-starter-session-jdbc` есть в Boot
  4.1.2 (тянет `spring-boot-session-jdbc` и `spring-session-jdbc` 4.1.x); свойства `spring.session.jdbc.*`:
  `initialize-schema` (по умолчанию `embedded` — для PostgreSQL ничего не создаёт, но задаём `never` явно),
  `flush-mode` `on-save`, `save-mode` `on-set-attribute`, `cleanup-cron` `0 * * * * *`, `table-name`
  `SPRING_SESSION`. Эталонная схема PostgreSQL в `spring-session-jdbc` совпадает со спекой §2.2. Сериализацию
  атрибутов `JdbcHttpSessionConfiguration` берёт из бина `springSessionConversionService` (`ConversionService`),
  если он есть. Cookie Spring Session (`DefaultCookieSerializer`) хранит id сессии в Base64 — тесты декодируют
  значение cookie, прежде чем искать строку по `session_id`.
- Веб-клиент: `ApiError.code` — любая строка (`HTTP_<status>` без тела); незнакомый код показывается как обычная
  ошибка по статусу (`describeActionFailure`, `isTransient` для `5xx`). Новых кодов нет, веб-клиент не меняется.
- MockMvc не выполняет перенаправление на `/error` (это делает контейнер сервлетов): исключение из фильтра выходит из
  `perform(...)`. Поэтому интеграционные тесты проверяют обработчик `/error` прямым запросом с атрибутами ошибки, а
  цепочку «фильтр упал → `/error` → Problem Details» целиком — ручная проверка на стенде (шаг 6).

## Шаг 1. Спека

**Что делаем**
- `docs/specs/jdbc-sessions.md`: статус «согласовано, в работе»; §2.6, §4, §7 (Q14) — прочие `4xx` получают имя
  HTTP-статуса по правилу `ApiExceptionHandler` (как уже описано в контракте), нового кода нет; §3 — тест `/error`
  прямым запросом (MockMvc не перенаправляет); добавить раздел «Отличия реализации» с цифрами NF08 «до» из
  `docs/load-report.md` (прогоны 1 и 2: p95 чтения 195,5 / 186,9 мс, изменения 188,4 / 102,7 мс).

**Тесты:** нет (только документ).

**Готово, когда:** спека не противоречит коду и контракту.

## Шаг 2. Обработчик `/error` в формате Problem Details

Не зависит от сессий и полезен сам по себе: любая ошибка вне контроллеров начинает отвечать как остальной API.

**Тесты (сначала, красные)**

Unit, `ErrorCodesTest` (новый, `common/web`): отображение статуса в код.

| Тест | Ожидание |
|---|---|
| `serverErrorsAreInternalError` | `500`, `502`, `503` → `INTERNAL_ERROR` |
| `ownCodesForAuthAndNotFound` | `401` → `UNAUTHENTICATED`, `403` → `FORBIDDEN`, `404` → `NOT_FOUND` |
| `otherClientErrorsAreTheStatusName` | `400` → `BAD_REQUEST`, `405` → `METHOD_NOT_ALLOWED`, `415` → `UNSUPPORTED_MEDIA_TYPE`; нестандартный `499` → `HTTP_499` |

Красный: класса нет (не компилируется).

Интеграционные, `ErrorPageTests` (новый, `@IntegrationTest`, `common/web`). Запрос `GET /error` с атрибутами
`jakarta.servlet.error.status_code`, `jakarta.servlet.error.exception`, `jakarta.servlet.error.request_uri`, как их
ставит контейнер; заголовок `Contract.SKIP_HEADER` («/error is an internal error page, not an API path»).

| Тест | Ожидание | Почему красный сейчас |
|---|---|---|
| `serverErrorIsProblemDetailsWithoutInternals` | `500`, `Content-Type: application/problem+json`, `code = INTERNAL_ERROR`, `detail` нейтральный, текста исключения в теле нет | `BasicErrorController`: JSON без `code` |
| `clientErrorKeepsItsStatusAndGetsTheStatusName` | статус `405` → `405`, `code = METHOD_NOT_ALLOWED` | то же |
| `notFoundIsNotFound` | статус `404` → `code = NOT_FOUND` | то же |
| `errorPageWithoutStatusIsInternalError` | без атрибутов → `500 INTERNAL_ERROR` | то же |

**Код**
- `common/web/ErrorCodes` (или статический метод рядом с `statusCodeName`): статус → код. `ApiExceptionHandler`
  переходит на тот же метод для имени статуса — одно правило в одном месте.
- `common/web/ProblemErrorController implements ErrorController`, `@RequestMapping("/error")`: статус из атрибута
  запроса (нет — `500`), тело через `Problems`/`ProblemResponseWriter`; `5xx` пишется в журнал `ERROR` с исключением
  из атрибута, тело без подробностей. `ProblemResponseWriter.write` получает перегрузку с кодом-строкой, если она
  понадобится для кода по имени статуса.

**Гейт шага:** `./gradlew :dispatch:test`.

## Шаг 3. Таблицы сессий (`V14__http_sessions.sql`)

**Тесты (сначала, красные)**

`SchemaMigrationTests` (или новый `HttpSessionSchemaTests`, `@IntegrationTest`, по образцу `ProblemsMigrationTests`):

| Тест | Ожидание | Почему красный сейчас |
|---|---|---|
| ожидаемый список миграций | `"1"` … `"14"` | `14` нет |
| `attributeWithoutSessionIsRejected` | `INSERT` в `spring_session_attributes` с несуществующим `session_primary_id` → нарушение `spring_session_attributes_fk` | таблиц нет |
| `deletingASessionDeletesItsAttributes` | сессия + атрибут, `DELETE` сессии → атрибутов нет | таблиц нет |
| `sessionIdIsUnique` | две строки с одним `session_id` → нарушение `spring_session_ix1` | таблиц нет |

Тесты удаляют свои строки в `@AfterEach`.

**Код:** миграция по спеке §2.2 (эталонный скрипт, имена в нижнем регистре, ограничения и индексы названы явно).

**Гейт шага:** `./gradlew :dispatch:test`.

## Шаг 4. Сессии в БД, cookie `SESSION`

**Тесты (сначала, красные)**

`AuthTests` переходит с `MockHttpSession` на cookie: помощник `loginCookie(username)` возвращает cookie `SESSION` из
ответа на вход, запросы передают её `.cookie(...)`; помощник `sessionRow(cookie)` декодирует Base64 и читает строку
`spring_session` по `session_id` через `JdbcClient`.

| Тест | Ожидание | Почему красный сейчас |
|---|---|---|
| `meWorksWithTheLoginSession` (меняется) | вход → `GET /api/auth/me` с cookie `SESSION` → `200`, пользователь | cookie `SESSION` нет (есть только `MockHttpSession`) |
| `loginStoresTheSessionInTheDatabase` | после входа есть строка с этим `session_id`, `principal_name = north.initiator`, `expiry_time − last_access_time = 30 мин` | таблица пуста: сессия в памяти |
| `sessionStoresPrincipalWithoutPasswordHash` (меняется) | `SessionRepository.findById(id)` → контекст → `CurrentUser` с `passwordHash = null` | сессии в репозитории нет |
| `loginChangesTheSessionId` | вход → cookie 1; повторный вход с cookie 1 → cookie 2 с другим id; строки с id 1 нет, запрос с cookie 1 — `401`, с cookie 2 — `200` | cookie `SESSION` нет |
| `logoutEndsTheSession` (меняется) | выход → `204`, строки нет, `GET /api/auth/me` со старой cookie → `401 UNAUTHENTICATED` | то же |
| `unknownSessionIsUnauthenticated` (охранный) | cookie `SESSION` с id несуществующей сессии → `401 UNAUTHENTICATED` | проходит и сейчас (cookie игнорируется); после перехода защищает от `500` на неизвестный id |
| `expiredSessionIsUnauthenticated` | после входа `UPDATE spring_session SET expiry_time = <в прошлом>` → `401` | таблицы с сессией нет |
| `csrfEndpointTokenIsAcceptedByLogin` (меняется) | вместо `MockHttpSession` переносит cookie `SESSION`, если она есть | — |

Остальные тесты `AuthTests` и все тесты на `TestSecurity.asUser` не меняются и проходят.

Веб (на старом коде красные после смены контракта):
- `web/e2e/auth/session-lost.spec.ts`: `clearCookies({ name: 'SESSION' })`;
- `web/src/shared/api/client.test.ts`: `SESSION=s` в тестовых данных.

**Код**
- `dispatch/build.gradle.kts`: `spring-boot-starter-session-jdbc`.
- `application.yml`: `spring.session.jdbc.initialize-schema: never`.
- `contracts/dispatch-api.yaml`: `securitySchemes.session.name: SESSION`; «Session and CSRF»: сессия хранится на
  сервере в БД, переживает перезапуск `dispatch`, истекает через 30 минут без запросов. `make contracts-lint`,
  `make web-api-types`.

**Гейт шага:** `./gradlew build`, `make contracts-lint`, `make web-build`; `make up && make e2e`, `make web-e2e`.

## Шаг 5. Нечитаемая сессия считается отсутствующей

**Тесты (сначала, красные)**

Unit, `SessionAttributeConversionTest` (`auth`):

| Тест | Ожидание | Почему красный сейчас |
|---|---|---|
| `roundTripsTheSecurityContext` | контекст с `CurrentUser` → байты → тот же контекст | класса нет |
| `unreadableBytesBecomeNull` | случайные байты и сериализованный объект неизвестного класса → `null`, без исключения | класса нет |

Интеграционные, `AuthTests` (`OutputCaptureExtension`):

| Тест | Ожидание | Почему красный сейчас |
|---|---|---|
| `unreadableSessionIsUnauthenticated` | после входа `UPDATE spring_session_attributes SET attribute_bytes = '\x00ff'` → `GET /api/auth/me` → `401 UNAUTHENTICATED`, не исключение; в выводе `WARN` «Session attribute SPRING_SECURITY_CONTEXT could not be read, treated as absent», без байтов и id; строка сессии на месте | стандартный конвертер бросает `SerializationFailedException`, `perform` падает |
| `databaseFailureWhileReadingASessionIsNotUnauthenticated` (охранный) | репозиторий сессий бросает `DataAccessResourceFailureException` на `findById` → `perform` выбрасывает исключение с этой причиной, а не отвечает `401`; `GET /actuator/health` без cookie — `200` | проходит сразу: проверяет, что конвертер ловит только ошибки десериализации. Проверка теста: временно ловить в конвертере всё — тест должен покраснеть |

Для охранного теста `JdbcIndexedSessionRepository` добавляется в `@MockitoSpyBean(types = …)` аннотации
`@IntegrationTest` (как `InboxDao`, сбрасывается после каждого теста). Если шпион на этом классе не работает
(внутренний класс сессии держит ссылку на репозиторий), тест подменяет `JdbcOperations` репозитория через
`SessionRepositoryCustomizer` в тестовой конфигурации — без изменений рабочего кода.

**Код:** `auth/SessionAttributeConversion` — бин `springSessionConversionService`: `Object → byte[]` — стандартный
`SerializingConverter`; `byte[] → Object` — `DeserializingConverter` с загрузчиком классов приложения, ошибка
десериализации → `WARN` и `null`.

**Гейт шага:** `./gradlew build`.

## Шаг 6. Документы, ручная проверка, нагрузка

**Что делаем**
- `README.md`: «Демо-пользователи» — сессии в БД, переживают перезапуск `dispatch`, истекают через 30 минут без
  запросов; известные ограничения спеки §5 (кратко, со ссылкой). «Падение и перезапуск» — убрать «войдите заново».
- `docs/dispatch-design.md`: §1 — обработчик `/error`; §2 — абзац «Сессии в памяти» заменяется решением и
  ограничениями; §4 — таблицы сессий и отступления от правил схемы.
- `CLAUDE.md`: `auth` — сессии в PostgreSQL через Spring Session JDBC, cookie `SESSION`; `common/web` — обработчик
  `/error`; в правилах схемы — исключение для `spring_session*`; тесты `AuthTests` переносят cookie `SESSION`.
- `docs/manual-testing.md`: убрать «войдите заново» (разделы 3, 6); в раздел 3 — проверка выживания сессии.
- Спека: «Отличия реализации» — что отличилось и цифры NF08 «после».

**Ручная проверка на стенде** (`make up`, миграция применяется к базе с данными без `make reset`):
1. Вход в двух окнах → `make dispatch-kill` → `make dispatch-start` → обновить страницу: пользователь на месте.
2. `make db-kill` → в окне ошибки загрузки, на вход не уводит; `curl` с cookie сессии на `/api/auth/me` →
   `500`, `application/problem+json`, `code = INTERNAL_ERROR`; `curl localhost:8080/actuator/health` без cookie
   отвечает (`DOWN` из-за базы — это проверка базы, а не сессий). `make db-start` → обновить страницу: вход не нужен.
3. Выход → строки сессии нет (`select count(*) from spring_session where principal_name = '<логин>'`).
4. В журнале `dispatch` нет id сессий и cookie.

**Нагрузка:** `make down`, `make load-reset && make load-up && make load-seed`, `make load-test` — пороги NF08
выполнены, цифры в спеку. Прогон дважды, как для `docs/load-report.md`.

**Гейт:** `./gradlew build`, `make up && make e2e`, `make contracts-lint`, `make web-build`, `make web-e2e`, ручная
проверка, `make load-test`.

## Вне объёма

Как в спеке §5–§6: абсолютный предел, renewal, «Запомнить меня», список сеансов, JSON-сериализация, перенос сессий
при выкатке, перезапуск контейнеров из e2e.
