# Спека: сверка с перевозчиком

Статус: согласовано (второй опрос, 17.11.2026). План — `docs/plans/reconciliation-plan.md`. Основание: ТЗ D05 (сверка; неоднозначность после
потери ответа видна оператору; расхождения не затираются без описанного правила и фиксируемого решения), D06
(результат сверки в UI, оператор повторяет допустимую операцию и видит исход), D-NF03 (расхождения выделяются для
разбора; история связывает задание, попытки, внешний результат и решение), D04 (нет бесконечного цикла повторов).
Способ сверки ТЗ оставляет исполнителю (п. 91). Дизайн `dispatch` — `docs/dispatch-design.md`; эта спека меняет его
§4 (модель данных), §5 (уникальность `inbox`), §6 (сверка) и §7 (виды проблем и действия).

Опирается на реализованную спеку (a) `docs/specs/operator-problems.md`: таблица `problem` (V9), бюджет повторов
`retry_from` и применённая запись `applied_inbox_id` (V10), `ProblemRules`, `ProblemService`, `OperatorActions`
(`retry`, `resolve`), `OperatorResults.applyChosen`, права и `Idempotency-Key` с целью действия в отпечатке запроса.
Здесь они расширяются, не переопределяются. Воспроизведение на стенде, `make seed` и ручные сценарии — спека (c).

## 1. Зачем

Лента — единственный путь, которым к нам приходит правда перевозчика. Если она отстала, запись в ней испорчена
(`malformed` → `INVALID`) или упала у нас при разборе (`FAILED`), итог до заявки не дойдёт никогда. Если ответ на
отправку потерян (`loseResponse`), доставка висит в `UNKNOWN`. Если перевозчик потерял принятую операцию или наш итог
разошёлся с его итогом, мы сейчас об этом не узнаем вовсе.

Сверка спрашивает перевозчика напрямую (`GET /operations/{operationId}`), сравнивает его состояние с нашим и:
- догоняет его, если мы отстали (`EXTERNAL_AHEAD`), — через общий обработчик результатов, как запись ленты;
- снимает неоднозначность доставки: перевозчик знает операцию → `ACKNOWLEDGED`;
- ускоряет повтор отправки, если перевозчик операцию не знает, а доставка `UNKNOWN`;
- закрывает проблемы, которые ответ перевозчика сделал неактуальными;
- остальное показывает оператору как проблему и ничего не меняет сама.

Так же сверяют внешние системы в индустрии: правду забирают API внешней системы и пропускают через тот же код, что и
обычные события, а не правят запись вручную; неудачный запрос — незаконченная работа, а не вывод «объекта нет»;
неоднозначное уходит в очередь разбора, где у решения есть автор и причина.

## 2. Решение

### 2.1 Когда и кого сверяем

| Запуск | Когда | Кого |
|---|---|---|
| `STARTUP` | один проход после старта приложения (`ApplicationReadyEvent`) | состав фона, чей срок настал |
| `SCHEDULED` | проход раз в `reconcile-interval` (1 мин) | состав фона, чей срок настал |
| `MANUAL` | оператор, «Сверить сейчас» | одна заявка, в любом состоянии, без учёта срока |

**Состав фона** — операции, у которых:
- доставка `UNKNOWN` или `FAILED` (последняя попытка могла дойти);
- доставка `ACKNOWLEDGED`, итога нет (`business_status` не `SUCCEEDED`/`REJECTED`), `acknowledged_at` старше
  `reconcile-threshold` (2 мин);
- открыта проблема `EXTERNAL_MISSING` или `RECONCILIATION_CONFLICT` (система закрывает их по ответу перевозчика,
  §2.8, поэтому их надо перепроверять, даже когда итог у заявки уже есть).

**Срок** — `dispatch_operation.next_reconcile_at`: пусто (ни разу не сверяли) или `<= now`. За проход — не больше
`reconcile-per-pass` (100) операций, `ORDER BY next_reconcile_at NULLS FIRST, request_id`.

**Растущая пауза.** После каждой записанной проверки (любого запуска, кроме `ERROR` из-за недоступности — см. ниже)
ставятся `reconciled_at = now` и `next_reconcile_at = now + пауза`:
- ответ такой же, как в предыдущей проверке этой операции, — пауза удваивается
  (`2 × (прежний next_reconcile_at − прежний reconciled_at)`), но не больше `reconcile-pause-max` (1 ч);
- иначе (первая проверка или ответ изменился) — `reconcile-pause` (5 мин).

«Такой же ответ» — совпадают исход, `carrier_state`, `carrier_result_status` и наш статус доставки. Так зависшая
операция сверяется 5 → 10 → 20 → 40 → 60 → 60 … минут: около 30 строк в сутки вместо 288, а любое изменение снова
сверяется часто.

**Недоступность перевозчика** в фоне останавливает проход: строка `ERROR` по этой операции пишется, пауза ей — базовая
(`reconcile-pause`), остальные операции ждут следующего прохода. Недоступность — не свойство операции, растить её паузу
незачем.

«Повторить» оператора (§2.7) очищает `reconciled_at` и `next_reconcile_at`: заявка начинает обмен заново, старая пауза к
ней не относится.

Фон и `STARTUP` работают, только когда `app.exchange.enabled = true`; ручная сверка работает всегда, как и остальной
API. Настройки — в `ExchangeProperties`:

| Свойство | По умолчанию |
|---|---|
| `app.exchange.reconcile-interval` | `1m` |
| `app.exchange.reconcile-threshold` | `2m` |
| `app.exchange.reconcile-pause` | `5m` |
| `app.exchange.reconcile-pause-max` | `1h` |
| `app.exchange.reconcile-per-pass` | `100` |

### 2.2 Запрос к перевозчику

`CarrierClient` получает метод `lookup(UUID operationId)` (HTTP знает только `HttpCarrierClient`). Таймауты те же,
что у отправки (2 с соединение, 5 с ответ). Результат — один из:

| Ответ перевозчика | Результат `lookup` |
|---|---|
| `200`, тело по схеме `OperationState`, `operationId` совпадает, `result` (если есть) прошёл `ResultMessage.parse` | `Found(state, result, raw)` |
| `404` | `NotFound(code, message, raw)` — код и текст из тела `Error`, если оно по схеме |
| соединение не установлено, таймаут, `503`, прочие 5xx | исключение `CarrierUnavailableException` |
| любой другой ответ или тело не по контракту | `Invalid(reason, code, message, raw)` |

Тело ответа хранится дословно и без усечения (`text`, как `inbox.payload`): решение оператора опирается на то, что
перевозчик действительно ответил, а итог перевозчика для `APPLY_CARRIER` берётся из него (§2.7).

`result` по контракту — итог или `null`, пока операция не исполнена. Защитно: `result` со статусом `IN_PROGRESS`
считается «итога нет».

**Описание (`details`)** есть у каждой строки сверки и собирается так: наша причина или вывод («нет поля `state`»,
«`operationId` не совпадает», «у перевозчика итога нет, у нас `SUCCEEDED`»), затем код и текст перевозчика, если он
их прислал (`OPERATION_NOT_FOUND: Operation … is not known`), затем начало дословного ответа (до 500 символов,
`SafeText`). Оно видно в ответе «Сверить сейчас» и в истории сверок заявки.

### 2.3 Одна проверка: порядок и транзакции

**Фон (`STARTUP`, `SCHEDULED`):**
1. `lookup` — вне транзакции.
2. Транзакция: заблокировать строку операции (`SELECT … FOR UPDATE`, как `InboxDao.lockTarget`), прочитать
   **текущее** состояние доставки и итога заявки, решить исход чистой функцией `ReconciliationRules.decide` (§2.4),
   записать строку `reconciliation`, выполнить эффекты (§2.5), поставить `reconciled_at`/`next_reconcile_at`.

**Ручная (`MANUAL`)** — всё в транзакции `Idempotency-Key` (`IdempotencyService` требует транзакцию вызывающего): повтор
с тем же ключом возвращает сохранённый ответ и перевозчика повторно не спрашивает. Внутри: сначала `lookup` (строка
операции ещё не заблокирована, соединение с базой занято не дольше таймаутов), затем шаг 2 как в фоне. Недоступность
перевозчика здесь не исключение, а исход `ERROR`: он записан и возвращён `200`, иначе откатился бы ключ вместе со
строкой. Чтобы спросить снова, клиент отправляет новый ключ.

**Итог из `EXTERNAL_AHEAD`** обрабатывается в той же транзакции шага 2 (§2.5 п. 2): `InboxProcessor.process(inboxId)` —
та же логика, что у `processDue`, для одной записи, со своей вложенной точкой сохранения (`PROPAGATION_NESTED`). Строку
операции мы уже держим, фоновый обработчик незакоммиченную запись не видит — гонки с ним нет, а ответ «Сверить сейчас»
уже отражает итог.

**Гонка с лентой.** Пока шёл `lookup`, лента могла применить итог. Решение принимается по нашему состоянию *после*
блокировки строки операции, а обработчик результатов блокирует ту же строку. Состояние перевозчика монотонно
(`ACCEPTED → EXECUTED`, итог не меняется), поэтому устареть может только наше, и оно перечитано. Пример: лента
применила итог между шагами 1 и 2 → под блокировкой итог уже есть и совпадает → `MATCH`.

**Гонка с воркером отправки.** Эффекты на доставку — условные `UPDATE … WHERE delivery_status IN (…)`, как в
`InboxDao.applyToOperation`. Если воркер держит операцию в `SENDING`, подтверждение сверкой его обгоняет, а запись
исхода воркера не срабатывает (огорожена `delivery_status = 'SENDING' AND attempts = N`) — как при подтверждении
результатом.

**Несколько экземпляров** могут сверить одну операцию одновременно: строки `reconciliation` пишутся обе, эффекты
условные, проблемы защищены частичными уникальными индексами. Порядок блокировок прежний: строка операции, затем
строка проблемы.

### 2.4 Исходы

Сравнение итогов — **по содержимому** (статус, номер отправления, стоимость, причина отказа) в формате
`ResultMessage.content()`; `messageId`, `sequence`, `occurredAt` в сравнение не входят. Наш итог берётся из
`delivery_request` — то, что видит пользователь.

`ReconciliationRules.decide(наше состояние, ответ перевозчика)`:

| Перевозчик | У нас | Исход |
|---|---|---|
| недоступен | любое | `ERROR` |
| ответ не по контракту | любое | `ERROR` |
| `404` | доставка `PENDING`, `RETRY_WAIT`, `SENDING` | `MATCH` — мы и не утверждаем, что доставили |
| `404` | доставка `UNKNOWN`, `FAILED`, `ACKNOWLEDGED` | `EXTERNAL_MISSING` |
| знает, итога нет | итога нет | `MATCH` |
| знает, итога нет | итог есть | `CONFLICT` (у перевозчика итога нет) |
| знает, итог есть | итога нет | `EXTERNAL_AHEAD` |
| знает, итог есть | итог есть, содержимое совпадает | `MATCH` |
| знает, итог есть | итог есть, содержимое другое | `CONFLICT` |

Отдельно от исхода во всех строках «перевозчик знает операцию» действуют эффекты §2.5 п. 1.

### 2.5 Эффекты

Все — в транзакции шага 2 §2.3, вместе со строкой `reconciliation`.

1. **Перевозчик знает операцию** (любой `Found`):
   - условный переход `delivery_status IN ('PENDING', 'SENDING', 'RETRY_WAIT', 'UNKNOWN', 'FAILED') → ACKNOWLEDGED`,
     `acknowledged_via = 'RECONCILIATION'`, `acknowledged_at = now`, `locked_until` и `last_error` очищаются. Если
     переход сработал — `ProblemService.onDeliveryStatus(ACKNOWLEDGED)` закрывает системой `DELIVERY_UNKNOWN` и
     `DELIVERY_FAILED` (правило (a)), в строке `acknowledged = true`. Это и есть разрешение неоднозначности после
     потери ответа (D05);
   - **независимо от перехода** открытая `EXTERNAL_MISSING` закрывается системой: перевозчик показал, что знает
     операцию. (Привязать её закрытие к переходу нельзя: она открыта только у доставки, которая уже `ACKNOWLEDGED`,
     и переход для неё не срабатывает никогда.)
2. **`EXTERNAL_AHEAD`:** `result` из ответа (дословно, как объект) передаётся в `ResultInbox.receive(raw,
   RECONCILIATION)` и сразу обрабатывается (§2.3): строгая проверка, `ResultRules`, `APPLIED`. Отдельного пути
   применения у сверки нет. Строка `reconciliation` ссылается на запись `inbox` (`inbox_id`). Если запись с этим
   `(source, message_id)` уже есть (итог уже приходил сверкой и, например, разобран как `CONFLICT`), новая не
   создаётся, строка ссылается на существующую, в `details` — её статус.
3. **`EXTERNAL_MISSING`:**
   - доставка `UNKNOWN` → отправить сразу: `next_attempt_at = now`, бюджет повторов не меняется (если попытки
     кончатся — `FAILED`). Перевозчик `operationId` не знает, а повтор с тем же `operationId` идемпотентен у него в
     любом случае;
   - доставка `FAILED` → автоматически ничего (§2.6);
   - доставка `ACKNOWLEDGED` → открыть проблему `EXTERNAL_MISSING`, если нет открытой. Перевозчик подтвердил приём, а
     теперь операцию не знает — он потерял её у себя; решает оператор (§2.7).
4. **`CONFLICT`:**
   - если по заявке открыта `RECONCILIATION_CONFLICT` — её `reconciliation_id` и `details` переставляются на эту
     строку: проблема всегда показывает последний ответ перевозчика, и набор решений считается по нему (§2.7);
   - иначе, если последняя закрытая `RECONCILIATION_CONFLICT` заявки закрыта решением `KEEP_OURS`, а ответ
     перевозчика по содержимому тот же (оба без итога или итоги совпадают) и наш итог с тех пор не менялся
     (`local_result` той строки и текущей совпадает; `version` заявки не годится — она растёт и от правки
     комментария), —
     проблема не открывается, в `details` строки — «решено ранее: проблема <id>»;
   - иначе открыть `RECONCILIATION_CONFLICT` со ссылкой на эту строку.
   Ни итог, ни доставка не меняются.
5. **`MATCH`:** если по заявке открыта `RECONCILIATION_CONFLICT` — закрыть её системой (итоги сошлись: перевозчик
   исполнил операцию с нашим итогом или наш итог заменили его версией).
6. **`ERROR`:** только строка `reconciliation`.

### 2.6 Почему `FAILED` + `404` не повторяется сам

Попытки `FAILED` уже исчерпаны. Автоматический повтор без нового бюджета дал бы одну попытку, после неё снова `FAILED`,
и через паузу всё сначала — медленный бесконечный цикл, который запрещает D04. Новый бюджет — решение оператора. Исход
`EXTERNAL_MISSING` при этом полезен: он в истории и говорит оператору, что «Повторить» безопасно — перевозчик операцию
не исполнял. Проблема `DELIVERY_FAILED` уже открыта (a).

### 2.7 Действия оператора

Один набор действий на все проблемы; в карточке и в проблеме видны только применимые сейчас.

| Действие | Смысл | Доступно |
|---|---|---|
| «Сверить сейчас» (`RECONCILE`) | перепросить перевозчика | всегда |
| «Повторить отправку» (`RETRY`) | перевозчик заявку не получил или потерял | доставка `FAILED` или `UNKNOWN` (a); доставка `ACKNOWLEDGED`, если открыта `EXTERNAL_MISSING` и итога у заявки нет |
| «Принять версию перевозчика» (`APPLY_CARRIER`) | наш итог заменяется его итогом | `RESULT_CONFLICT` с итоговой спорной записью (a); `RECONCILIATION_CONFLICT`, если в его последней строке у перевозчика есть итог |
| «Оставить наш» (`KEEP_OURS`, комментарий обязателен) | наш итог остаётся, расхождение принято осознанно | `RESULT_CONFLICT` (a), `RECONCILIATION_CONFLICT`; `EXTERNAL_MISSING`, если итог у заявки есть |
| «Разобрано» (`ACKNOWLEDGED`, комментарий обязателен) | выбирать нечего, принято к сведению | `INVALID_MESSAGE`, `MESSAGE_FAILED` (a) |

Сознательно нет: ручного ввода итога (источник правды о результате — только перевозчик, содержимое заявки
неизменяемо), отмены заявки (в протоколе перевозчика её нет), статуса «в работе» и эскалации (для этого есть
исполнитель и комментарий заявки).

**«Сверить сейчас»** — `POST /api/requests/{id}/reconcile`, без тела:
- оператор службы заявки; инициатор — `403` на свою, `404` на чужую; оператор другой службы — `404`;
  `Idempotency-Key` обязателен, цель (`requestId`) — в отпечатке запроса, операция `RECONCILE`;
- проверка по §2.3 с запуском `MANUAL` и автором; ответ `200` с объектом `Reconciliation` (§2.10), в том числе при
  `ERROR`. Недопустимого состояния нет.

**«Повторить отправку»** для `ACKNOWLEDGED` + `EXTERNAL_MISSING` без итога: `delivery_status = PENDING`,
`retry_from = attempts` (новый бюджет, (a) §2.2.1), `next_attempt_at = now`, `acknowledged_at`/`acknowledged_via`
очищаются, `operationId` прежний. Проблема `EXTERNAL_MISSING` закрывается решением `RETRIED`. Если итог у заявки есть —
`409 INVALID_STATE`: повторная отправка исполненной доставки дала бы второй бизнес-эффект (D03). Наличие итога и
открытой проблемы проверяется под блокировкой строки операции. Любой «Повторить» очищает `reconciled_at` и
`next_reconcile_at` (§2.1).

**`resolve` для новых видов:**

| Вид | Решение | Что делает |
|---|---|---|
| `RECONCILIATION_CONFLICT` | `APPLY_CARRIER` | Итог перевозчика (`result` из `carrier_response` последней строки проблемы) записывается в `inbox` через `ResultInbox.receive(raw, RECONCILIATION)` — или берётся существующая запись с тем же `(source, message_id)` — и применяется готовым `OperatorResults.applyChosen` из (a): итог заявки заменяется, `version + 1`, запись `APPLIED`, `applied_inbox_id` указывает на неё, `last_applied_seq = max(текущий, sequence перевозчика)`. Повтор этого итога в ленте потом — `STALE`, а не новый конфликт. Без итога у перевозчика — `409 INVALID_STATE` |
| `RECONCILIATION_CONFLICT` | `KEEP_OURS` | закрыть проблему; итог не меняется |
| `EXTERNAL_MISSING` | `KEEP_OURS` (только при итоге у заявки) | закрыть проблему: итог остаётся, потеря — на стороне перевозчика (оператор разобрался с ним) |

Почему «принять версию перевозчика» без итога у него невозможно: это значило бы стереть готовый итог, который уже
видел инициатор, и вернуть заявку в ожидание; а настоящий итог перевозчика потом отсеялся бы как устаревший. Вместо
этого проблема остаётся открытой, фон её перепроверяет, и когда перевозчик исполнит операцию, либо итоги совпадут
(система закроет, §2.5 п. 5), либо появится `APPLY_CARRIER`.

Замена итога, `version + 1` и закрытие проблемы — одна транзакция под блокировкой строки операции, как для
`RESULT_CONFLICT` в (a). Строки `reconciliation` после записи не меняются.

### 2.8 Закрытие проблем (дополнение к таблице (a))

| Вид | Система закрывает | Оператор закрывает |
|---|---|---|
| `DELIVERY_UNKNOWN`, `DELIVERY_FAILED` | как в (a); подтверждение сверкой — такой же переход в `ACKNOWLEDGED` | как в (a) |
| `EXTERNAL_MISSING` | сверка получила `Found`, или обработчик `inbox` применил (`APPLIED`) запись этой операции — перевозчик её знает | «Повторить» (`RETRIED`) без итога; «Оставить наш» (`KEEP_OURS`) при итоге |
| `RECONCILIATION_CONFLICT` | сверка дала `MATCH` | `APPLY_CARRIER` / `KEEP_OURS` |

Закрытие при `APPLIED` вызывает `InboxProcessor` через `ProblemService` после точки сохранения, в транзакции записи, —
независимо от того, сработал ли при этом переход доставки.

### 2.9 Модель данных — миграция `V11__reconciliation.sql`

**Таблица `reconciliation`** — по строке на каждую записанную проверку одной операции, не удаляется и не меняется:

| Колонка | Содержимое |
|---|---|
| `id` | UUIDv7 |
| `request_id` | FK `delivery_request`, `NOT NULL` |
| `operation_id` | FK `dispatch_operation (operation_id)`, `NOT NULL` |
| `checked_at` | `timestamptz NOT NULL` |
| `run_type` | `STARTUP` / `SCHEDULED` / `MANUAL` |
| `performed_by` | FK `app_user`; заполнен тогда и только тогда, когда `run_type = 'MANUAL'` |
| `outcome` | `MATCH` / `EXTERNAL_AHEAD` / `EXTERNAL_MISSING` / `CONFLICT` / `ERROR` |
| `acknowledged` | `boolean NOT NULL`: эта проверка подтвердила доставку |
| `delivery_status` | наш статус доставки на момент решения (для истории и «такого же ответа», §2.1) |
| `local_result` | наш итог на момент решения в каноническом виде `ResultMessage.content()`; пусто, если итога нет (для истории и правила §2.5 п. 4 «наш итог с тех пор не менялся») |
| `carrier_state` | `ACCEPTED` / `EXECUTED` / `NOT_FOUND`; пусто при `ERROR` |
| `carrier_result_status`, `carrier_tracking_number`, `carrier_price`, `carrier_reason`, `carrier_sequence`, `carrier_message_id`, `carrier_occurred_at` | итог перевозчика, разобранный; пусто, если итога нет |
| `carrier_response` | дословное тело ответа (`text`, без усечения); пусто, если ответа не было |
| `details` | `text NOT NULL`, §2.2 |
| `inbox_id` | FK `inbox`: запись итога при `EXTERNAL_AHEAD` |

Ограничения (с именами, каждое «вслепую» к значениям других правил, как в V9):
- допустимые значения `run_type`, `outcome`, `carrier_state`, `carrier_result_status` (`SUCCEEDED`/`REJECTED`),
  `delivery_status`;
- `MANUAL` ⇔ `performed_by IS NOT NULL`;
- `ERROR` ⇔ `carrier_state IS NULL`;
- `EXTERNAL_MISSING` ⇒ `carrier_state = 'NOT_FOUND'`;
- `CONFLICT` ⇒ `carrier_state IN ('ACCEPTED', 'EXECUTED')` (итог перевозчика не обязателен — §2.4);
- `EXTERNAL_AHEAD` ⇒ итог перевозчика есть и `inbox_id IS NOT NULL`;
- итог перевозчика есть ⇒ `carrier_state = 'EXECUTED'`; `SUCCEEDED` — номер и стоимость, `REJECTED` — причина (как у
  заявки);
- `acknowledged` ⇒ `carrier_state IN ('ACCEPTED', 'EXECUTED')`.

Индекс `(request_id, checked_at DESC)` — история заявки и предыдущая проверка операции.

**`dispatch_operation`:** `reconciled_at timestamptz`, `next_reconcile_at timestamptz`; ограничения «оба пусты или оба
заполнены» и `next_reconcile_at >= reconciled_at`. Индекс под выборку фона по `next_reconcile_at`.

**`problem`:**
- колонка `reconciliation_id` (FK `reconciliation`);
- пересоздаются три ограничения:
  - `problem_kind_valid` — плюс `EXTERNAL_MISSING`, `RECONCILIATION_CONFLICT`;
  - `problem_source_matches_kind` — у прежних видов `reconciliation_id IS NULL`; `EXTERNAL_MISSING` — `request_id`,
    без `inbox_id` и `reconciliation_id`; `RECONCILIATION_CONFLICT` — `request_id` и `reconciliation_id`, без
    `inbox_id`;
  - `problem_decision_matches_kind` — `SYSTEM`: виды доставки, `EXTERNAL_MISSING`, `RECONCILIATION_CONFLICT`;
    `RETRIED`: `DELIVERY_FAILED`, `EXTERNAL_MISSING`; `APPLY_CARRIER`: `RESULT_CONFLICT`, `RECONCILIATION_CONFLICT`;
    `KEEP_OURS`: `RESULT_CONFLICT`, `RECONCILIATION_CONFLICT`, `EXTERNAL_MISSING`; `ACKNOWLEDGED`: `INVALID_MESSAGE`,
    `MESSAGE_FAILED`;
- `problem_open_per_request_kind` не меняется: он задан условием `inbox_id IS NULL` и уже даёт «одна открытая на
  заявку и вид» обоим новым видам.

**`inbox`:** `inbox_message_id_unique (message_id)` заменяется на `inbox_source_message_id_unique
(source, message_id) WHERE message_id IS NOT NULL`. Иначе итог из сверки схлопнулся бы с испорченной записью ленты с
тем же `messageId` и не применился бы никогда. Повтор между источниками ловят правила: запись ленты с тем же
`sequence` и содержимым после итога из сверки — `STALE`. `InboxDao.countDuplicate` и `insertReceived`
(`ON CONFLICT`) переходят на пару `(source, message_id)`.

### 2.10 API

`contracts/dispatch-api.yaml`:
- `POST /api/requests/{id}/reconcile` — заголовок `Idempotency-Key`, без тела; `200` → `Reconciliation`
  (заголовок `Idempotent-Replayed`, как у других действий); `400` без ключа, `401`, `403`, `404`, `422` — как у
  остальных действий.
- Схема `Reconciliation`: `id`, `checkedAt`, `trigger` (`STARTUP`/`SCHEDULED`/`MANUAL`), `performedBy` (`PersonRef`
  или `null`), `outcome`, `acknowledged`, `carrierState` (или `null`), `carrierResult` (`status`, `trackingNumber`,
  `price`, `reason`, `sequence`, `messageId`, `occurredAt`; или `null`), `details`.
- `RequestCard` оператора: поле `reconciliations` — последние 20 проверок заявки, новые первыми (D06: результат
  сверки в UI); у инициатора поля нет, как у `problems`. Полная лента событий — пункт 5 плана.
- `RequestAction`: плюс `RECONCILE` (у оператора всегда); `RETRY` — по §2.7. `ProblemRules.requestActions` получает,
  кроме статуса доставки, открытые проблемы заявки и признак итога.
- `ExchangeProblem`: новые значения `kind`. Для `RECONCILIATION_CONFLICT` версия перевозчика — в том же поле
  `message`, что у `RESULT_CONFLICT` (поля из последней строки сверки; `feedSeq` пуст; начало `carrier_response`), а
  `currentResult` — наш итог: интерфейс показывает оба конфликта одним блоком «их версия / наш итог». Если у
  перевозчика итога нет, поля итога в `message` пусты, а `details` это объясняет. Для `EXTERNAL_MISSING` заполнен
  `currentResult`.
- `retry` и `resolve` — описания допустимых состояний дополняются по §2.7.

`contracts/carrier-api.yaml`: фраза «a result applied from reconciliation is later recognised as a duplicate in the
feed» заменяется: повтор между источниками — правило применимости (тот же `sequence` и содержимое — без эффекта).
Протокол перевозчика не меняется.

После правки контракта — `make contracts-lint`, `make web-api-types`, в тестовых данных web — `actions` с `RECONCILE`
и `reconciliations` где нужно.

### 2.11 Журнал (NF10)

- В конце непустого прохода фона — `INFO` одной строкой: запуск, проверено, количество по исходам
  (`Reconciliation SCHEDULED: checked 12 — MATCH 9, EXTERNAL_AHEAD 1, EXTERNAL_MISSING 0, CONFLICT 1, ERROR 1`).
- `INFO` на каждое подтверждение доставки и `EXTERNAL_AHEAD` (с `operationId`); `WARN` на `EXTERNAL_MISSING` и
  `CONFLICT` (с `operationId` и `details`).
- Перевозчик недоступен — `WARN` «Reconciliation stopped: carrier unavailable», проход прекращается.
- Ручная сверка — `INFO` с логином оператора, `operationId` и исходом.
- Тело ответа перевозчика в журнал не пишется (оно в `reconciliation.carrier_response`).

## 3. Тесты

**Модульные (`*Test`):**

| Тест | Что проверяет |
|---|---|
| `ReconciliationRulesTest` | каждая строка таблицы §2.4; совпадение при разной форме цены (`612.5` и `612.50`); разные `occurredAt`/`messageId` — `MATCH`; `IN_PROGRESS` в `result` — «итога нет»; расчёт паузы (первая, такой же ответ — удвоение, потолок, изменился ответ — сброс) |
| `ProblemRulesTest` (дополнение) | решения для `EXTERNAL_MISSING` (с итогом — `KEEP_OURS`, без — нет) и `RECONCILIATION_CONFLICT` (с итогом перевозчика — оба, без — только `KEEP_OURS`); `requestActions`: `RECONCILE` всегда, `RETRY` для `ACKNOWLEDGED` + `EXTERNAL_MISSING` без итога |
| `HttpCarrierClientTest` (дополнение) | `lookup`: `200` по контракту, `404` с кодом и текстом, `503` и обрыв → исключение, `200` с чужим `operationId` / испорченным `result` / не JSON → `Invalid` с причиной |

**Интеграционные (`*Tests`, `@IntegrationTest`, `FakeCarrier` получает программируемый `lookup`):**

| Тест | Ожидание |
|---|---|
| `UNKNOWN`, перевозчик `ACCEPTED` | `ACKNOWLEDGED` via `RECONCILIATION`, `DELIVERY_UNKNOWN` закрыта системой, строка `MATCH` с `acknowledged = true`, повтора отправки нет |
| `FAILED`, перевозчик `EXECUTED` с итогом | подтверждение + `EXTERNAL_AHEAD`: итог применён в той же транзакции, `DELIVERY_FAILED` закрыта, запись `inbox` с `source = RECONCILIATION` в `APPLIED` |
| запись ленты `INVALID`, затем сверка | итог применён; позже лента приносит исправную запись с тем же `messageId` — `STALE`, итог и `version` не меняются |
| `UNKNOWN`, `404` | `EXTERNAL_MISSING`, `next_attempt_at = now`, бюджет прежний; следующий проход воркера отправляет |
| `FAILED`, `404` | `EXTERNAL_MISSING`, состояние не меняется, проблемы не открываются |
| `ACKNOWLEDGED` без итога, `404` | проблема `EXTERNAL_MISSING`, в карточке `RETRY`; «Повторить» → `PENDING`, `retry_from = attempts`, `attempts` прежний, сроки сверки очищены, проблема `RETRIED`; повтор той же сверки до «Повторить» не открывает вторую |
| `EXTERNAL_MISSING`, затем перевозчик `ACCEPTED` | проблема закрыта системой, хотя доставка уже была `ACKNOWLEDGED` |
| `EXTERNAL_MISSING`, затем итог по ленте | запись `APPLIED`, проблема закрыта системой |
| `ACKNOWLEDGED` с итогом, `404` (ручная) | `EXTERNAL_MISSING`; «Повторить» → `409`; `KEEP_OURS` без комментария → `400`, с комментарием закрывает |
| итоги разные | `RECONCILIATION_CONFLICT`, итог не изменён; повторная сверка — вторая строка, проблема одна и ссылается на новую строку; `APPLY_CARRIER` — итог перевозчика, `version + 1`, запись `inbox` `APPLIED`, затем та же запись по ленте — `STALE`; `KEEP_OURS` — без изменений |
| после `KEEP_OURS` тот же ответ | новая строка `CONFLICT` с «решено ранее», проблема не открыта; другой ответ перевозчика — открыта |
| «у нас итог, у перевозчика нет» | `RECONCILIATION_CONFLICT`, в `message` поля итога пусты; `APPLY_CARRIER` → `409`; затем перевозчик исполнил с нашим итогом — фон даёт `MATCH`, проблема закрыта системой; с другим итогом — `APPLY_CARRIER` доступен |
| гонка с лентой | `FakeCarrier.lookup` во время вызова применяет итог через `TestResults.deliver` → `MATCH`, второй записи `APPLIED` нет |
| перевозчик недоступен в фоне | строка `ERROR` по первой операции с базовой паузой, остальные не тронуты, проход остановлен |
| ответ не по контракту | `ERROR`, `details` содержит причину, код и текст перевозчика и начало ответа; проблем нет |
| состав фона и пауза | берёт `UNKNOWN`, `FAILED`, `ACKNOWLEDGED` без итога старше порога, открытые `EXTERNAL_MISSING`/`RECONCILIATION_CONFLICT`; не берёт моложе порога, с итогом без проблем, `PENDING`/`RETRY_WAIT`/`SENDING`, с ненаступившим сроком; лимит за проход; порядок `next_reconcile_at NULLS FIRST`; такой же ответ удваивает паузу до потолка, изменившийся — сбрасывает |
| права и идемпотентность `reconcile` | инициатор: `403` своя, `404` чужая; оператор другой службы — `404`; тот же ключ — сохранённый ответ, `lookup` второй раз не вызван; `ERROR` тоже сохраняется; без ключа — `400` |
| карточка | у оператора `reconciliations` (новые первыми, не больше 20) и `RECONCILE`; у инициатора поля нет |
| схема | `SchemaMigrationTests`: каждое новое ограничение `reconciliation`, `dispatch_operation` и `problem` отвергает неверные данные; уникальность `(source, message_id)`; ожидаемый список миграций с V11 |

Фон вызывается напрямую (`Reconciler.runOnce(trigger)`), как `SendWorker.runOnce()`; планировщик в тестах выключен
(`app.exchange.enabled=false`). Переход `UNKNOWN → ACKNOWLEDGED` сверкой проверяется только интеграционно: на стенде
он гоняется с повторной отправкой.

**e2e (`ReconciliationE2ETest`, против стенда):**
- `delayed` через `CarrierClient`: создать заявку, дождаться `ACKNOWLEDGED`, «Сверить сейчас» до публикации в ленте
  (задержка 60 с) → `EXTERNAL_AHEAD`, итог в карточке сразу после ответа; у эмулятора `executionCount = 1`; позже
  запись ленты не меняет итог и `version`;
- обычная заявка с итогом: «Сверить сейчас» → `MATCH`, проверка видна в `reconciliations`;
- права: инициатор получает `403` на `reconcile`, в карточке нет `reconciliations`.

Конфликты и `EXTERNAL_MISSING` на стенде — ручные сценарии спеки (c) (`psql` в базе `carrier`).

## 4. Что меняется

| Где | Что |
|---|---|
| `db/migration/V11__reconciliation.sql` | §2.9 |
| `carrier/CarrierClient`, `HttpCarrierClient` | `lookup(operationId)` → `Found` / `NotFound` / `Invalid` |
| `exchange/ExchangeProperties` | `reconcileInterval`, `reconcileThreshold`, `reconcilePause`, `reconcilePauseMax`, `reconcilePerPass` |
| новый пакет `exchange/reconcile` | `Reconciler` (фон, `STARTUP`, одна проверка), `ReconciliationRules` (чистая: исход и пауза), `ReconciliationDao` |
| `exchange/inbox/ResultInbox`, `InboxDao` | дедупликация по `(source, message_id)` |
| `exchange/inbox/InboxProcessor` | `process(inboxId)`; при `APPLIED` — закрытие `EXTERNAL_MISSING` через `ProblemService` |
| `exchange/SendQueueDao` | `retryByOperator` для `ACKNOWLEDGED` + `EXTERNAL_MISSING` без итога; очистка сроков сверки |
| `problem` | виды `EXTERNAL_MISSING`, `RECONCILIATION_CONFLICT`; решения и `requestActions` по §2.7; `ProblemService` — закрытие по ответу перевозчика и по `MATCH`, перестановка ссылки конфликта; `OperatorActions.reconcile` и `POST /api/requests/{id}/reconcile` |
| `request` | `reconciliations` в карточке оператора |
| `contracts/dispatch-api.yaml`, `carrier-api.yaml` | §2.10 |
| `docs/dispatch-design.md` | §4, §5, §6, §7 по этой спеке — первым docs-коммитом |
| тесты | §3; `FakeCarrier` — программируемый `lookup` |

## 5. Вне объёма

- Экраны: «Сверить сейчас», история сверок, конфликт с двумя версиями — web-спека отдельно.
- Лента событий заявки и `audit_log` — пункт 5.
- Воспроизведение на стенде, `make seed`, документ ручных проверок — спека (c).
- Сверка пачкой (перевозчик отдаёт только по одной операции).

## 6. Решения опроса

| # | Решение |
|---|---|
| Q1 | `FAILED` + `404` без автоматического повтора (§2.6) |
| Q2 | «итог только у нас» — `CONFLICT`; сейчас только `KEEP_OURS`, фон перепроверяет, `MATCH` закрывает, итог перевозчика открывает `APPLY_CARRIER` |
| Q3 | `EXTERNAL_MISSING` закрывается по факту «перевозчик знает операцию», не по смене статуса доставки |
| Q5 | ответ не по контракту — `ERROR` без проблемы, с полным описанием в `details` |
| Q6 | растущая пауза 5 мин → 1 ч, `next_reconcile_at` |
| Q7 | ручная сверка внутри транзакции ключа, `ERROR` — обычный ответ |
| Q8 | итог `EXTERNAL_AHEAD` обрабатывается в транзакции сверки |
| Q9 | версия перевозчика конфликта сверки — в `ExchangeProblem.message` |
| Q10 | `RECONCILE` всегда, `RETRY` по §2.7 |
| Q11 | после `KEEP_OURS` тот же ответ проблему не открывает |
| Q12 | `APPLY_CARRIER` сверки — запись в `inbox` + `OperatorResults.applyChosen` |
| Q13 | `UNKNOWN → ACKNOWLEDGED` сверкой — только интеграционно |
| Q14 | FK `reconciliation.operation_id` |
| Q15 | единый набор из пяти действий (§2.7); `EXTERNAL_MISSING` при итоге — `KEEP_OURS` |
