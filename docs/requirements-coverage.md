# Покрытие ТЗ — требование → реализация → проверка

Основание: `04-system-integration.md` (v2.1). Состояние на 18.11.2026 (после merge `jdbc-sessions`). Таблица
собрана по коду, спекам и списку тестов; гейт для неё отдельно не прогонялся.

Обозначения: ✅ закрыто реализацией и тестами; ⚠️ реализовано, но проверка неполная или есть что уточнить.

## Функциональные требования

| ID | Требование (кратко) | Где реализовано | Чем проверено | |
|---|---|---|---|---|
| D01 | Создание → передача → результат; доставка ≠ результат; принятое сохраняется при недоступности | `RequestService` (заявка и `dispatch_operation` в одной транзакции), `SendWorker`, `FeedPoller` → `ResultInbox` → `InboxProcessor`; `DeliveryStatus` отдельно от результата | `RequestCreateTests`, `SendWorkerTests`, `FeedPollerTests`, `InboxProcessorTests`; e2e `ExchangeE2ETest`, `RequestsE2ETest`; web `requests/flows.spec.ts` | ✅ |
| D02 | Контракт обмена, идентификация операции, правила применимости результатов | `contracts/carrier-api.yaml` (с правилами обмена), `operationId` (UUIDv7, неизменяем), `ResultRules`, `ResultMessage`; `docs/dispatch-design.md` §5 | `ResultRulesTest`, `ResultMessageTest`, `HttpCarrierClientTest`; эмулятор — `Contract.matchesContract()` во всех тестах; `make contracts-lint` | ✅ |
| D03 | Повтор доставки не исполняет дважды; повтор результата без эффекта; устаревший не затирает | Эмулятор: идемпотентный `POST /operations` по `operationId` + `ContentHash`, `Executor` (условный `UPDATE`); dispatch: `InboxDao` уникален по `(source, message_id)`, `ResultRules` (`STALE`, сравнение с `applied_inbox_id`) | Эмулятор: `SubmitTests`, `ExecutionTests`, `LoseResponseTests`, `DuplicateTests`; dispatch: `ResultInboxTests`, `ResultRulesTest`, `InboxProcessorTests`; e2e `CarrierFaultsE2ETest`, `ExchangeFaultsE2ETest` | ✅ |
| D04 | Переживает недоступность и перезапуск; управляемый повтор; некорректное → разбор; без быстрого бесконечного цикла | `RetryPolicy` (ограниченный бюджет, паузы), захват с фенсингом по номеру попытки, `FeedEnvelope` (битая страница не принимается), `INVALID` → проблема `INVALID_MESSAGE`; `make dispatch-kill` / `db-kill` | `RetryPolicyTest`, `SendWorkerTests` (окна падения, истёкший захват), `FeedPollerTests`, `MessageProblemsTests`, `MalformedTests`; ручная процедура README «Падение и перезапуск» | ✅ |
| D05 | Сверка; неоднозначность после потери ответа видна оператору; расхождения не затираются без правила и решения | `Reconciler`, `ReconciliationRules`, `ReconciliationSchedule`; проблемы `EXTERNAL_MISSING`, `RECONCILIATION_CONFLICT`, `RESULT_CONFLICT`; решение оператора `OperatorResults` с комментарием | `ReconcilerTests`, `ReconcileConflictTests`, `ReconcileAheadTests`, `ReconcileDeliveryTests`, `ReconciliationRulesTest`, `ResolveReconciliationProblemTests`, `DeliveryProblemsTests`; e2e `ReconciliationE2ETest`, `ProblemsE2ETest` | ✅ |
| D06 | В UI: задания, история обмена, проблемы, сверка; оператор повторяет и видит исход; инициатор без прав оператора и чужих заданий | `web/`: реестр, карточка, «История», `/problems`, «Повторить отправку», «Сверить сейчас»; сервер: `RequestPermissions`, `OperatorActions` | `RequestAccessTests`, `ProblemAccessTests`, `RetryActionTests`, `ReconcileActionTests`; web Vitest (`RetryAction`, `ReconcileAction`, `RequestHistory`, `ProblemListPage`), Playwright `card-history`, `card-reconcile`, `problems/list`, `problems/faults`; e2e `HistoryE2ETest` | ✅ |
| D07 | Эмулятор: воспроизводимые недоступность, потеря ответа, дубликат, задержка; состояние и число исполнений отдельно | `fault/` (`unavailable`, `loseResponse`, `duplicate`, `delayed`, + `malformed`), `GET/PUT /admin/faults`, `GET /admin/operations` (`executionCount`); `make fault-*`, `make carrier-ops` | `FaultsTests`, `UnavailableTests`, `LoseResponseTests`, `DuplicateTests`, `DelayedTests`, `MalformedTests`, `OperationStateTests`; e2e `CarrierFaultsE2ETest` | ✅ |

## Нефункциональные требования

| ID | Требование (кратко) | Где реализовано | Чем проверено | |
|---|---|---|---|---|
| NF01 | Веб + API + реляционная БД; правила на сервере; данные реальные | React-клиент, Spring Boot API, PostgreSQL; демо-данные только через API (`make seed`) | e2e на стенде, Playwright против Docker-стенда | ✅ |
| NF02 | Демо-вход; ограничение по роли и области; прямой запрос к API не обходит | `SecurityConfig`, сессия в PostgreSQL, `RequestPermissions`, область службы у оператора, проверка в SQL реестра/карточки | `AuthTests`, `RequestAccessTests`, `RequestPermissionsTest`, `ProblemAccessTests`; e2e `AuthE2ETest`; web `auth/guard.spec.ts` | ✅ |
| NF03 | Переживает перезапуск приложения и БД; атомарность; целостность; нет незаметной перезаписи | Транзакции на действие, именованные `CHECK`/FK/уникальные индексы, `version` (оптимистичная блокировка), сессии в БД | `SchemaMigrationTests` и миграционные тесты, `RequestUpdateTests` (409 по версии), `RequestHistoryInvariantTests`; web `card-conflict.spec.ts`; ручная процедура рестарта | ✅ |
| NF04 | Повтор действия не даёт второго результата; независимые различаются; работает и через API | `Idempotency-Key` на создании и действиях оператора (`IdempotencyService`, хеш запроса с целью); в web ключ привязан к содержимому/намерению | `IdempotencyServiceTests`, `RequestCreateTests`, `RetryActionTests`, `ResolveProblemTests`; web `create/idempotency.test.ts`, `shared/actions/idempotency.test.ts` | ✅ |
| NF05 | Действия с автором, временем, объектом, изменением; история только уполномоченным; переживает смену ответственного/состояния/рестарт | `request_event` (версия + `[{field, from, to}]`), история из таблиц обмена, `HistoryService` (доступ как к карточке, поля по роли) | `RequestHistoryTests`, `RequestEventDaoTests`, `RequestHistoryInvariantTests`, `HistoryRulesTest`, `ChangesTest`; e2e `HistoryE2ETest`; web `card-history.spec.ts` | ✅ |
| NF06 | 390–1440 px; подписи, понятные ошибки, пустые состояния, состояние выполнения; клавиатура | Mantine-формы с подписями, свои тексты ошибок, пустые состояния и загрузка на экранах, раскладка таблица/карточки | Ширина: `list-phone`, `card-phone`, `create-phone`, `card/phone.test.tsx`. Клавиатура: `e2e/app/keyboard.spec.ts` (вход → создание с календарём и «Проверьте данные» → строка реестра → комментарий; ответственный, фильтр и сброс, выход — только Tab/Enter/Space/стрелки), `card-reconcile.spec.ts`. Пустые состояния и загрузка: `RequestListPage`, `ProblemListPage`, `RequestCardPage`, `ReconciliationHistory`, `RequestHistory` (Vitest) | ✅ |
| NF07 | Единый часовой пояс; одинаковые границы и просрочка в UI, API, расчётах | `app.timezone` (`Europe/Moscow`), `BusinessCalendar`, `OverduePolicy`; web берёт пояс с сервера (`useBusinessTimezone`, `todayIn`) | `BusinessCalendarTest`, `OverduePolicyTest`, `RequestListQueryTest`; web `shared/system.test.ts`, `requests/format.test.ts` | ✅ |
| NF08 | 5000 записей, 20 клиентов: p95 чтения ≤ 2 с, изменения ≤ 3 с; ≤ 2 CPU / 4 ГиБ | k6 в `load/`, стенд `exchange-load`, лимиты в `docker-compose.yml` | `make load-test`, отчёт `docs/load-report.md` (два полных прогона; повторный замер после сессий в БД) | ✅ |
| NF09 | Воспроизводимый запуск, схема, демо-данные; без платных сервисов | Docker Compose, Flyway при старте, `make up`, `make seed`, README «Запуск» | Гейт `make up && make e2e` | ✅ |
| NF10 | Автопроверки правил, прав, повторов, одновременных изменений; часть на реальной БД; техжурнал без паролей и ключей | Три уровня тестов, Testcontainers PostgreSQL; журнал — SLF4J (`logging.level` в `application.yml`), сбои эмулятора `WARN Fault …` | Гонки: `SendWorkerTests`, `InboxProcessorTests`, `IdempotencyServiceTests`, `ResolveProblemTests`, `RetryActionTests`. Журнал: `TechnicalLogTests` — вход (успешный, неверный пароль, ошибка проверки полей), CSRF-токен, отказ в доступе, неожиданная 500; в выводе нет пароля, cookie `SESSION`, id сессии, CSRF-токена и хеша BCrypt, а вход и ошибка в журнале есть | ✅ |

## Специальные требования

| ID | Требование (кратко) | Где реализовано | Чем проверено | |
|---|---|---|---|---|
| D-NF01 | Принятое не теряется при недоступности и рестарте; ожидающие продолжаются; подтверждение ≠ результат | Outbox `dispatch_operation`, захват с истечением, курсор ленты в БД | `SendWorkerTests`, `FeedPollerTests`, `UnavailableTests`; e2e `ExchangeFaultsE2ETest`; ручная процедура рестарта | ✅ |
| D-NF02 | Исполнение не более одного раза, в т.ч. при потере ответа; повтор результата без эффекта; устаревшее не откатывает | См. D03 | См. D03; `executionCount = 1` проверяется в e2e через `CarrierClient` | ✅ |
| D-NF03 | Ограниченные повторы; некорректное и расхождения на разбор; история связывает задание, попытки, результат, решение | `RetryPolicy`, проблемы, `HistoryQueryDao` (`request_event`, `exchange_attempt`, `inbox`, `reconciliation`, `problem`) | `RetryPolicyTest`, `MessageProblemsTests`, `RequestHistoryTests`; web `RequestHistory.test.tsx` | ✅ |

## Разделы 6–10

| Раздел | Требование | Где | |
|---|---|---|---|
| §6 UI | Реестр, создание, карточка, история обмена, проблемы, повтор, сверка; управление сбоями командами | `web/`; сбои — `make fault-*` | ✅ |
| §7 Демо-данные | 2 инициатора, оператор, ≥ 6 заданий с разными исходами, режимы сбоев | `V5__demo_users.sql`; `make seed` — 11 заявок, часть через режимы сбоев и сценарии эмулятора (`docs/specs/demo-seed.md`) | ✅ |
| §8 Границы | Эмулятор, восстановление, сверка обязательны | Есть все три | ✅ |
| §9 Допущения | Непротиворечивые допущения зафиксированы в README | README: допущения разбросаны по разделам (проблемы, история, эмулятор, нагрузка); полный список — `docs/dispatch-design.md` §10 | ⚠️ проверить, что все допущения из §10 есть в README |
| §10 Состав | Код, схема, демо-данные, запуск, автопроверки, инструкция с пользователями и командами | Всё в репозитории; README «Запуск», «Демо-пользователи», «Тесты» | ✅ |
| §10 Описание | Устройство, модель данных, API, допущения, ограничения | Устройство и модель данных — `docs/dispatch-design.md` (§4 «Модель данных»), API — README «API» + `contracts/`, ограничения — README | ⚠️ в README нет раздела про модель данных, нужна хотя бы ссылка на §4 |

## Что осталось

1. **§9, §10.** В README: раздел или ссылка «Модель данных» (`docs/dispatch-design.md` §4) и сверка допущений с
   `docs/dispatch-design.md` §10.

NF06 и NF10 закрыты тестами 18.11.2026 (`e2e/app/keyboard.spec.ts`, `TechnicalLogTests`); гейт после них зелёный.
