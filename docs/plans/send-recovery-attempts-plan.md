# План: прерванная отправка подчиняется лимиту попыток и паузам

Основание: спека `docs/specs/send-recovery-attempts.md` (опрос Q1–Q7). Ветка `send-recovery-attempts` от `main`.
Работа по TDD: в каждом шаге сначала пишутся тесты шага и запускаются, чтобы увидеть, что они падают **по нужной
причине** (красные), затем код до зелёного, затем рефакторинг. Рабочего кода без падающего теста нет. Один шаг — один
коммит; после каждого шага проходит гейт шага.

## 0. Исходное состояние

- `SendWorker.recover()` в одной транзакции вызывает `SendQueueDao.recoverExpired(now)` — один `UPDATE`: все
  `SENDING` с `locked_until < now` → `UNKNOWN`, `next_attempt_at = now`, без учёта `attempts` — и
  `ExchangeAttemptDao.finishExpired` (закрывает попытки, открытые дольше срока захвата). Журнал: одна строка `WARN`
  «Recovered N operation(s) …».
- Решение «повторить или `FAILED`» — только в `SendWorker.retryOrFail` (`RetryPolicy.forSending`, разброс ±20 %
  через `Math::random`).
- В рабочем дереве лежит незакоммиченный временный тест ревью `ReviewSendFindingsTests` (пункт 3): он подтверждает
  дефект (`attempts = maxAttempts + 1`). В шаге 2 его сценарий становится обычным тестом, файл удаляется.
- Тесты восстановления в `SendWorkerTests` изображают падение строкой в `SENDING` с истёкшим `locked_until` и открытой
  попыткой; два из них ждут немедленной повторной отправки (`sendInterruptedByACrashIsRecoveredAsUnknownAndResent`,
  `lateWorkerDoesNotOverwriteWhatHappenedAfterItsLease`).
- Настройки по умолчанию: `maxAttempts = 8`, `initialBackoff = 5s`, `maxBackoff = 5m`, `lease = 60s`.
  Пауза после попытки N с разбросом: `5 с × 2^(N−1)` (не больше 5 мин) × [0,8; 1,2]. После 1-й — 4–6 с,
  после 3-й — 16–24 с.

## Шаг 1. Документы дизайна

**Что делаем**
- `docs/dispatch-design.md` §5, пункт «Восстановление после падения»: операция в `SENDING` с истёкшим захватом —
  попытка с исходом `UNKNOWN` («Send was interrupted (lease expired)»); дальше по общим правилам повторов: пауза по
  номеру попытки или `FAILED`, если попытки кончились. Решение принимается в одном месте, условное обновление по
  номеру попытки. Ссылка на спеку.
- Спека: статус «согласовано».

**Тесты:** нет (только документы).

**Готово, когда:** текст дизайна совпадает со спекой §2.

## Шаг 2. Восстановление через `RetryPolicy`

**Тесты (сначала, красные)**

Интеграционные, `SendWorkerTests`. Помощник `crashedMidSend(request, attempt)`: строка операции в `SENDING` с
`attempts = attempt` и `locked_until = START − 1 с`, открытая попытка с `started_at = START − 61 с` (выносится из
повторяющегося кода нынешних тестов).

| Тест | Ожидание | Почему красный сейчас |
|---|---|---|
| `crashOnTheLastAttemptFailsTheOperation` | `runOnce() = 0`; `FAILED`, `attempts = 8`, `last_error = 'Attempts exhausted (8): Send was interrupted (lease expired)'`, `locked_until` пуст; `carrier.submitted()` пуст; попытка 8 закрыта `UNKNOWN` | восстановление ставит `UNKNOWN` сразу к отправке, уходит 9-я попытка |
| `crashedSendWaitsForItsPauseBeforeTheNextAttempt` | падение на 3-й попытке: `runOnce() = 0`, `UNKNOWN`, `attempts = 3`, `next_attempt_at − START` в [16 с; 24 с]; часы на `START + 15 с` — отправки нет; часы на `START + 25 с` — уходит 4-я попытка, `ACKNOWLEDGED` | операция уходит сразу при восстановлении |
| `recoveryLogsRescheduledAndFailedOperations` (`OutputCaptureExtension`) | две операции: одна упала на 1-й попытке, другая на последней; в выводе `WARN` «Rescheduled 1 operation(s) after an interrupted send: [<id1>]» и «Failed 1 operation(s) after an interrupted send, attempts exhausted: [<id2>]» | сейчас одна строка «Recovered 2 operation(s) …» |

Интеграционные, условное обновление (`SendWorkerTests`, вызов `SendQueueDao` напрямую — тот же пакет):

| Тест | Ожидание | Почему красный сейчас |
|---|---|---|
| `recoveryDoesNotTouchAnOperationThatMovedOn` | истёкшая операция найдена выборкой, затем её строка изменилась (результат подтвердил доставку: `ACKNOWLEDGED`; отдельный случай — `attempts` уже другой); обновления «переназначить» и `FAILED` с прежним номером попытки возвращают `false`, строка не изменилась | методов выборки и условных обновлений нет (не компилируется) |

Меняются существующие (на старом коде становятся красными, потому что ждут паузы):
- `sendInterruptedByACrashIsRecoveredAsUnknownAndResent`: первый `runOnce()` — `0` и `UNKNOWN`; часы за паузу
  (`START + 7 с`) — `runOnce() = 1`, дальше прежние проверки. Переименовать в
  `sendInterruptedByACrashIsResentAfterThePause`.
- `lateWorkerDoesNotOverwriteWhatHappenedAfterItsLease`: «другой экземпляр» внутри зависшего вызова сначала
  восстанавливает операцию (часы `START + 61 с`, `runOnce() = 0`), затем после паузы (`START + 68 с`) отправляет
  повторно (`runOnce() = 1`); проверки опоздавшего `202` прежние.

Без изменений и должны остаться зелёными: `attemptOfACrashedSendIsClosedAfterTheLeaseEvenWhenAResultConfirmedDelivery`
(подтверждённую операцию восстановление не трогает), `openAttemptIsKeptUntilTheLeaseIsOver`,
`liveLeaseIsNotTakenOver`, `parallelWorkersNeverSendOneOperationTwice`, остальные `SendWorkerTests`,
`RetryPolicyTest`.

`ReviewSendFindingsTests` удаляется (его сценарий — `crashOnTheLastAttemptFailsTheOperation`).

**Код**
- `SendQueueDao`:
  - `recoverExpired` удаляется;
  - `List<Expired> findExpired(Instant now)` — `SELECT request_id, operation_id, attempts FROM dispatch_operation
    WHERE delivery_status = 'SENDING' AND locked_until < :now ORDER BY locked_until FOR UPDATE SKIP LOCKED`;
  - `boolean rescheduleInterrupted(Expired, Instant next, String error, Instant now)` — `UNKNOWN`,
    `locked_until = NULL`, `next_attempt_at = :next`, `last_error`, `updated_at`;
  - `boolean failInterrupted(Expired, String error, Instant now)` — `FAILED`, `locked_until = NULL`, `last_error`,
    `updated_at`;
  - оба `WHERE request_id = :requestId AND delivery_status = 'SENDING' AND attempts = :attempt
    AND locked_until < :now`;
  - `record Expired(UUID requestId, UUID operationId, int attempt)`.
- `SendWorker.recover()`: в той же транзакции выбрать истёкшие; для каждой `retryPolicy.canRetry(attempt)` →
  `rescheduleInterrupted(now + backoff(attempt))`, иначе `failInterrupted("Attempts exhausted (N): " + INTERRUPTED)`;
  собрать два списка `operationId` (только сработавшие обновления) и записать журнал по спеке §2.3 (до 10 id, затем
  «…»). Текст «Send was interrupted (lease expired)» — одна константа. `finishExpired` и его журнал без изменений.
- Javadoc `SendWorker` (пункт 1 списка) и `SendQueueDao` — по новой логике.

**Документы**
- `docs/manual-testing.md` §5.3: повтор после падения — примерно через 65 с после создания (60 с захвата + ~5 с
  паузы), а не через 60 с; «запасной механизм» описан как попытка с неизвестным исходом.

**Гейт шага**
- `./gradlew build`;
- `make up && make e2e`;
- ручная проверка `docs/manual-testing.md` §5.3 на стенде (через API, как в прошлой работе): «Попыток отправки: 2»,
  повтор через ~65 с, `receivedCount = 2`, `executionCount = 1`, в журнале `dispatch` строка «Rescheduled 1
  operation(s) after an interrupted send».

**Готово, когда:** все тесты зелёные; падение на последней попытке даёт `FAILED` без новой отправки; после падения на
ранней попытке операция ждёт свою паузу.

## Вне объёма

- Повтор оператором со сбросом счётчика (§7 дизайна).
- Проверка `lease > connectTimeout + readTimeout` при старте (ревью, пункт 5).
- `408` / `429` (ревью, пункт 6).
- e2e на падение на последней попытке: на стенде не воспроизводится (спека §3).
