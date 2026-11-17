package com.acme.dispatch.exchange;

import static com.acme.dispatch.common.SqlTime.toSql;
import static com.acme.dispatch.support.TestClockConfiguration.START;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import com.acme.dispatch.auth.AppUserRepository;
import com.acme.dispatch.auth.CurrentUser;
import com.acme.dispatch.carrier.SubmitOutcome;
import com.acme.dispatch.carrier.SubmitRequest;
import com.acme.dispatch.carrier.SubmitResult;
import com.acme.dispatch.request.ExchangeInfo;
import com.acme.dispatch.request.RequestCard;
import com.acme.dispatch.request.RequestQueryService;
import com.acme.dispatch.support.DemoData;
import com.acme.dispatch.support.FakeCarrier;
import com.acme.dispatch.support.IntegrationTest;
import com.acme.dispatch.support.MutableClock;
import com.acme.dispatch.support.TestFeed;
import com.acme.dispatch.support.TestRequests;
import com.acme.dispatch.support.TestResults;
import com.acme.dispatch.support.TestRows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class SendWorkerTests {

    static final SubmitResult TEMPORARY = new SubmitResult(SubmitOutcome.TEMPORARY_ERROR, 503, "HTTP 503");
    static final SubmitResult UNKNOWN = new SubmitResult(SubmitOutcome.UNKNOWN, null, "No response in time");
    static final SubmitResult PERMANENT = new SubmitResult(SubmitOutcome.PERMANENT_ERROR, 400, "HTTP 400");
    static final SubmitResult CONFLICT = new SubmitResult(SubmitOutcome.PAYLOAD_CONFLICT, 409, "HTTP 409");
    static final String INTERRUPTED = "Send was interrupted (lease expired)";

    @Autowired
    SendWorker worker;
    @Autowired
    FakeCarrier carrier;
    @Autowired
    TestRequests requests;
    @Autowired
    DispatchOperationDao operations;
    @Autowired
    MutableClock clock;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    RequestQueryService cards;
    @Autowired
    AppUserRepository users;
    @Autowired
    ExchangeProperties properties;
    @Autowired
    TestResults results;
    @Autowired
    SendQueueDao queue;
    @Autowired
    TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        clock.set(START);
        carrier.reset();
    }

    @AfterEach
    void cleanUp() {
        TestRows.deleteRequestData(jdbc);
    }

    @Test
    void pendingOperationIsSentAndAcknowledged() {
        RequestCard request = requests.create("Pallets", "12.5", "Main st 1");

        assertThat(worker.runOnce()).isEqualTo(1);

        DispatchOperation operation = operation(request);
        assertThat(operation.deliveryStatus()).isEqualTo(DeliveryStatus.ACKNOWLEDGED);
        assertThat(operation.acknowledgedVia()).isEqualTo("RESPONSE");
        assertThat(operation.acknowledgedAt()).isEqualTo(START);
        assertThat(operation.attempts()).isEqualTo(1);
        assertThat(operation.lockedUntil()).isNull();

        SubmitRequest sent = carrier.submitted().getFirst();
        assertThat(sent.operationId()).isEqualTo(request.operationId());
        assertThat(sent.cargoDescription()).isEqualTo("Pallets");
        assertThat(sent.weightKg()).hasToString("12.5");
        assertThat(sent.deliveryAddress()).isEqualTo("Main st 1");
        assertThat(sent.deadline()).isEqualTo(TestRequests.DEADLINE);

        assertThat(attempts(request)).containsExactly("1:ACKNOWLEDGED:202:finished");
    }

    @Test
    void wholeWeightIsSentWithoutATrailingZero() {
        requests.create("20");

        worker.runOnce();

        assertThat(carrier.submitted().getFirst().weightKg()).hasToString("20");
    }

    @Test
    void temporaryErrorWaitsThenRetriesWithTheSameOperationId() {
        RequestCard request = requests.create("1");
        carrier.answer(TEMPORARY);

        worker.runOnce();

        DispatchOperation waiting = operation(request);
        assertThat(waiting.deliveryStatus()).isEqualTo(DeliveryStatus.RETRY_WAIT);
        assertThat(waiting.lastError()).isEqualTo("HTTP 503");
        assertThat(waiting.nextAttemptAt()).isBetween(START.plusSeconds(4), START.plusSeconds(6));

        assertThat(worker.runOnce()).as("not due yet").isZero();

        clock.advance(Duration.ofSeconds(6));
        assertThat(worker.runOnce()).isEqualTo(1);

        DispatchOperation delivered = operation(request);
        assertThat(delivered.deliveryStatus()).isEqualTo(DeliveryStatus.ACKNOWLEDGED);
        assertThat(delivered.attempts()).isEqualTo(2);
        assertThat(delivered.lastError()).isNull();
        assertThat(carrier.submitted()).extracting(SubmitRequest::operationId)
                .containsExactly(request.operationId(), request.operationId());
        assertThat(attempts(request)).containsExactly("1:TEMPORARY_ERROR:503:finished", "2:ACKNOWLEDGED:202:finished");
    }

    @Test
    void operatorCardShowsTheNextAttemptOnlyWhileQueued() {
        RequestCard request = requests.create("1");
        carrier.answer(TEMPORARY);
        CurrentUser operator = CurrentUser.from(users.findByUsernameIgnoreCase(DemoData.NORTH_OPERATOR_LOGIN).orElseThrow());

        worker.runOnce();
        ExchangeInfo waiting = cards.getCard(request.id(), operator).exchange();
        assertThat(waiting.nextAttemptAt()).isNotNull();
        assertThat(waiting.lastError()).isEqualTo("HTTP 503");

        clock.advance(Duration.ofSeconds(6));
        worker.runOnce();
        ExchangeInfo delivered = cards.getCard(request.id(), operator).exchange();
        assertThat(delivered.nextAttemptAt()).isNull();
        assertThat(delivered.attempts()).isEqualTo(2);
        assertThat(delivered.acknowledgedVia()).isEqualTo("RESPONSE");
    }

    @Test
    void unknownOutcomeIsResentWithTheSameOperationId() {
        RequestCard request = requests.create("1");
        carrier.answer(UNKNOWN);

        worker.runOnce();
        assertThat(operation(request).deliveryStatus()).isEqualTo(DeliveryStatus.UNKNOWN);

        clock.advance(Duration.ofSeconds(6));
        worker.runOnce();

        assertThat(operation(request).deliveryStatus()).isEqualTo(DeliveryStatus.ACKNOWLEDGED);
        assertThat(carrier.submitted()).extracting(SubmitRequest::operationId)
                .containsExactly(request.operationId(), request.operationId());
    }

    @Test
    void operationsAreSentInDueOrder() {
        List<UUID> created = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            created.add(requests.create(String.valueOf(i)).operationId());
            clock.advance(Duration.ofSeconds(1));
        }

        assertThat(worker.runOnce()).isEqualTo(6);

        // Each claim takes the most overdue operation, so a pass sends them oldest first.
        assertThat(carrier.submitted()).extracting(SubmitRequest::operationId).containsExactlyElementsOf(created);
    }

    @Test
    void permanentErrorAndPayloadConflictFailWithoutRetry() {
        RequestCard rejected = requests.create("1");
        clock.advance(Duration.ofSeconds(1));   // the queue is ordered by due time: rejected goes first
        RequestCard conflicting = requests.create("2");
        carrier.answer(PERMANENT, CONFLICT);

        worker.runOnce();
        clock.advance(Duration.ofHours(1));
        assertThat(worker.runOnce()).isZero();

        assertThat(operation(rejected).deliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(operation(rejected).lastError()).isEqualTo("HTTP 400");
        assertThat(operation(conflicting).deliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(attempts(conflicting)).containsExactly("1:PAYLOAD_CONFLICT:409:finished");
        assertThat(carrier.submitted()).hasSize(2);
    }

    @Test
    void operationFailsWhenAttemptsAreExhausted() {
        RequestCard request = requests.create("1");
        for (int i = 0; i < 8; i++) {
            carrier.answer(TEMPORARY);
        }

        for (int i = 0; i < 8; i++) {
            assertThat(worker.runOnce()).isEqualTo(1);
            clock.advance(Duration.ofMinutes(10));
        }
        assertThat(worker.runOnce()).isZero();

        DispatchOperation failed = operation(request);
        assertThat(failed.deliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(failed.attempts()).isEqualTo(8);
        assertThat(failed.lastError()).isEqualTo("Attempts exhausted (8): HTTP 503");
        assertThat(carrier.submitted()).hasSize(8);
    }

    @Test
    void sendInterruptedByACrashIsResentAfterThePause() {
        RequestCard request = requests.create("1");
        crashedMidSend(request, 1);

        assertThat(worker.runOnce()).as("recovered, waits for its pause").isZero();
        DispatchOperation recovered = operation(request);
        assertThat(recovered.deliveryStatus()).isEqualTo(DeliveryStatus.UNKNOWN);
        assertThat(recovered.lastError()).isEqualTo(INTERRUPTED);
        assertThat(recovered.lockedUntil()).isNull();
        assertThat(carrier.submitted()).isEmpty();

        clock.set(START.plusSeconds(7));
        assertThat(worker.runOnce()).isEqualTo(1);

        DispatchOperation operation = operation(request);
        assertThat(operation.deliveryStatus()).isEqualTo(DeliveryStatus.ACKNOWLEDGED);
        assertThat(operation.attempts()).isEqualTo(2);
        assertThat(attempts(request)).containsExactly("1:UNKNOWN:null:finished", "2:ACKNOWLEDGED:202:finished");
        assertThat(attemptError(request, 1)).isEqualTo(INTERRUPTED);
    }

    @Test
    void crashOnTheLastAttemptFailsTheOperation() {
        int max = properties.maxAttempts();
        RequestCard request = requests.create("1");
        crashedMidSend(request, max);

        assertThat(worker.runOnce()).isZero();

        DispatchOperation operation = operation(request);
        assertThat(operation.deliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(operation.attempts()).isEqualTo(max);
        assertThat(operation.lastError()).isEqualTo("Attempts exhausted (" + max + "): " + INTERRUPTED);
        assertThat(operation.lockedUntil()).isNull();
        assertThat(carrier.submitted()).isEmpty();
        assertThat(attempts(request)).containsExactly(max + ":UNKNOWN:null:finished");

        clock.set(START.plus(Duration.ofHours(1)));
        assertThat(worker.runOnce()).as("an operator decides").isZero();
        assertThat(carrier.submitted()).isEmpty();
    }

    @Test
    void crashedSendWaitsForItsPauseBeforeTheNextAttempt() {
        RequestCard request = requests.create("1");
        crashedMidSend(request, 3);

        assertThat(worker.runOnce()).isZero();
        DispatchOperation recovered = operation(request);
        assertThat(recovered.deliveryStatus()).isEqualTo(DeliveryStatus.UNKNOWN);
        assertThat(recovered.attempts()).isEqualTo(3);
        // The pause after attempt 3: 5 s × 2² = 20 s, ±20 %.
        assertThat(Duration.between(START, recovered.nextAttemptAt()))
                .isBetween(Duration.ofSeconds(16), Duration.ofSeconds(24));

        clock.set(START.plusSeconds(15));
        assertThat(worker.runOnce()).as("still within the pause").isZero();
        assertThat(carrier.submitted()).isEmpty();

        clock.set(START.plusSeconds(25));
        assertThat(worker.runOnce()).isEqualTo(1);
        DispatchOperation operation = operation(request);
        assertThat(operation.deliveryStatus()).isEqualTo(DeliveryStatus.ACKNOWLEDGED);
        assertThat(operation.attempts()).isEqualTo(4);
    }

    @Test
    void recoveryLogsRescheduledAndFailedOperations(CapturedOutput output) {
        RequestCard early = requests.create("1");
        RequestCard last = requests.create("2");
        crashedMidSend(early, 1);
        crashedMidSend(last, properties.maxAttempts());

        worker.runOnce();

        assertThat(output.getOut())
                .contains("Rescheduled 1 operation(s) after an interrupted send: [" + early.operationId() + "]")
                .contains("Failed 1 operation(s) after an interrupted send, attempts exhausted: ["
                        + last.operationId() + "]");
    }

    @Test
    void recoveryDoesNotTouchAnOperationThatMovedOn() {
        RequestCard confirmed = requests.create("1");
        RequestCard reclaimed = requests.create("2");
        crashedMidSend(confirmed, 1);
        crashedMidSend(reclaimed, 1);
        List<SendQueueDao.Expired> expired = transaction.execute(status -> queue.findExpired(START));
        assertThat(expired).extracting(SendQueueDao.Expired::operationId)
                .containsExactlyInAnyOrder(confirmed.operationId(), reclaimed.operationId());

        // Meanwhile a result confirmed one delivery, and another instance recovered and claimed the other again.
        jdbc.sql("""
                        UPDATE dispatch_operation SET delivery_status = 'ACKNOWLEDGED', locked_until = NULL,
                               acknowledged_at = :now, acknowledged_via = 'RESULT' WHERE request_id = :id""")
                .param("now", toSql(START))
                .param("id", confirmed.id())
                .update();
        jdbc.sql("""
                        UPDATE dispatch_operation SET attempts = 2, locked_until = :expired WHERE request_id = :id""")
                .param("expired", toSql(START.minusSeconds(1)))
                .param("id", reclaimed.id())
                .update();

        for (SendQueueDao.Expired operation : expired) {
            assertThat(queue.rescheduleInterrupted(operation, START.plusSeconds(5), INTERRUPTED, START)).isFalse();
            assertThat(queue.failInterrupted(operation, INTERRUPTED, START)).isFalse();
        }

        assertThat(operation(confirmed).deliveryStatus()).isEqualTo(DeliveryStatus.ACKNOWLEDGED);
        DispatchOperation stillSending = operation(reclaimed);
        assertThat(stillSending.deliveryStatus()).isEqualTo(DeliveryStatus.SENDING);
        assertThat(stillSending.attempts()).isEqualTo(2);
    }

    @Test
    void attemptOfACrashedSendIsClosedAfterTheLeaseEvenWhenAResultConfirmedDelivery() {
        RequestCard request = requests.create("1");
        crashMidSendThenResultFromFeed(request);
        assertThat(operation(request).acknowledgedVia()).isEqualTo("RESULT");
        assertThat(attempts(request)).as("nobody closed it yet").containsExactly("1:-:null:open");

        clock.set(START.plusSeconds(61));
        assertThat(worker.runOnce()).as("nothing to send").isZero();

        assertThat(attempts(request)).containsExactly("1:UNKNOWN:null:finished");
        assertThat(attemptError(request, 1)).isEqualTo(INTERRUPTED);
        DispatchOperation operation = operation(request);
        assertThat(operation.deliveryStatus()).isEqualTo(DeliveryStatus.ACKNOWLEDGED);
        assertThat(operation.attempts()).isEqualTo(1);
        assertThat(carrier.submitted()).as("no resend").isEmpty();
    }

    @Test
    void openAttemptIsKeptUntilTheLeaseIsOver() {
        RequestCard request = requests.create("1");
        crashMidSendThenResultFromFeed(request);

        clock.set(START.plusSeconds(59));
        worker.runOnce();
        assertThat(attempts(request)).containsExactly("1:-:null:open");

        clock.set(START.plus(Duration.ofSeconds(60)));
        worker.runOnce();
        assertThat(attempts(request)).as("exactly the lease is not past it").containsExactly("1:-:null:open");

        clock.set(START.plusSeconds(61));
        worker.runOnce();
        assertThat(attempts(request)).containsExactly("1:UNKNOWN:null:finished");
    }

    @Test
    void lateWorkerDoesNotOverwriteWhatHappenedAfterItsLease() {
        RequestCard request = requests.create("1");
        AtomicBoolean first = new AtomicBoolean(true);
        // The first call hangs past the lease; meanwhile another instance runs its pass.
        carrier.duringSubmit(() -> {
            if (first.getAndSet(false)) {
                clock.set(START.plusSeconds(61));
                assertThat(worker.runOnce()).as("the other instance recovers it").isZero();
                clock.set(START.plusSeconds(68));
                assertThat(worker.runOnce()).as("and resends after the pause").isEqualTo(1);
            }
        });

        assertThat(worker.runOnce()).isEqualTo(1);

        // The late 202 of attempt 1 neither reopened its record nor touched the operation.
        assertThat(attempts(request)).containsExactly("1:UNKNOWN:null:finished", "2:ACKNOWLEDGED:202:finished");
        assertThat(attemptError(request, 1)).isEqualTo(INTERRUPTED);
        DispatchOperation operation = operation(request);
        assertThat(operation.deliveryStatus()).isEqualTo(DeliveryStatus.ACKNOWLEDGED);
        assertThat(operation.attempts()).isEqualTo(2);
        assertThat(carrier.submitted()).extracting(SubmitRequest::operationId)
                .containsExactly(request.operationId(), request.operationId());
    }

    @Test
    void eachOperationIsClaimedRightBeforeItsCall() {
        for (int i = 0; i < 3; i++) {
            requests.create("1");
        }
        List<String> atCall = new ArrayList<>();
        // Every call takes 7 s (connect + read timeouts); record what the claim wrote when the call began.
        carrier.duringSubmit(() -> {
            UUID operationId = carrier.submitted().getLast().operationId();
            atCall.add(jdbc.sql("""
                            SELECT extract(epoch FROM a.started_at - :start)::int || ':'
                                   || extract(epoch FROM o.locked_until - a.started_at)::int
                              FROM dispatch_operation o
                              JOIN exchange_attempt a ON a.operation_id = o.operation_id
                                                     AND a.attempt_no = o.attempts
                             WHERE o.operation_id = :id""")
                    .param("start", toSql(START))
                    .param("id", operationId)
                    .query(String.class)
                    .single());
            clock.advance(Duration.ofSeconds(7));
        });

        assertThat(worker.runOnce()).isEqualTo(3);

        // started_at (seconds after START) : lease length (seconds) — the lease starts with each call.
        assertThat(atCall).containsExactly("0:60", "7:60", "14:60");
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM exchange_attempt
                         WHERE finished_at - started_at = make_interval(secs => duration_ms / 1000.0)""")
                .query(Integer.class).single())
                .as("started_at + duration = finished_at").isEqualTo(3);
    }

    @Test
    void onePassSendsAtMostSendsPerPass() {
        int perPass = properties.sendsPerPass();
        for (int i = 0; i <= perPass; i++) {
            requests.create("1");
        }

        assertThat(worker.runOnce()).isEqualTo(perPass);
        assertThat(worker.runOnce()).isEqualTo(1);
        assertThat(worker.runOnce()).as("nothing due").isZero();
    }

    @Test
    void liveLeaseIsNotTakenOver() {
        RequestCard request = requests.create("1");
        jdbc.sql("""
                        UPDATE dispatch_operation SET delivery_status = 'SENDING', attempts = 1,
                               locked_until = :lease WHERE request_id = :id""")
                .param("lease", toSql(START.plusSeconds(30)))
                .param("id", request.id())
                .update();

        assertThat(worker.runOnce()).isZero();
        assertThat(operation(request).deliveryStatus()).isEqualTo(DeliveryStatus.SENDING);
    }

    @Test
    void parallelWorkersNeverSendOneOperationTwice() throws Exception {
        for (int i = 0; i < 30; i++) {
            requests.create("1");
        }
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            List<Callable<Integer>> passes = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                passes.add(worker::runOnce);
            }
            for (Future<Integer> pass : pool.invokeAll(passes)) {
                pass.get();
            }
        } finally {
            pool.shutdownNow();
        }

        List<UUID> sent = carrier.submitted().stream().map(SubmitRequest::operationId).toList();
        assertThat(sent).hasSize(30).doesNotHaveDuplicates();
        assertThat(jdbc.sql("SELECT count(*) FROM dispatch_operation WHERE delivery_status = 'ACKNOWLEDGED'")
                .query(Integer.class).single()).isEqualTo(30);
    }

    @Test
    void retryBudgetCountsFromRetryFrom() {
        RequestCard request = requests.create("1");
        newBudgetAfter(request, properties.maxAttempts());
        carrier.answer(UNKNOWN);

        assertThat(worker.runOnce()).isEqualTo(1);

        DispatchOperation operation = operation(request);
        assertThat(operation.deliveryStatus()).as("first attempt of the new budget").isEqualTo(DeliveryStatus.UNKNOWN);
        assertThat(operation.attempts()).isEqualTo(properties.maxAttempts() + 1);
        // The pause after the first attempt of a budget: 5 s, ±20 %.
        assertThat(Duration.between(START, operation.nextAttemptAt()))
                .isBetween(Duration.ofSeconds(4), Duration.ofSeconds(6));
    }

    @Test
    void budgetRunsOutAfterMaxAttemptsFromRetryFrom() {
        int max = properties.maxAttempts();
        RequestCard request = requests.create("1");
        newBudgetAfter(request, max);
        jdbc.sql("UPDATE dispatch_operation SET attempts = :attempts WHERE request_id = :id")
                .param("attempts", 2 * max - 1)
                .param("id", request.id())
                .update();
        carrier.answer(TEMPORARY);

        assertThat(worker.runOnce()).isEqualTo(1);

        DispatchOperation operation = operation(request);
        assertThat(operation.deliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(operation.attempts()).isEqualTo(2 * max);
        assertThat(operation.lastError()).isEqualTo("Attempts exhausted (" + max + "): HTTP 503");
    }

    @Test
    void attemptNumbersContinueAfterANewBudget() {
        int max = properties.maxAttempts();
        RequestCard request = requests.create("1");
        newBudgetAfter(request, max);

        worker.runOnce();

        assertThat(attempts(request)).containsExactly((max + 1) + ":ACKNOWLEDGED:202:finished");
    }

    @Test
    void recoveryUsesTheBudgetToo() {
        int max = properties.maxAttempts();
        RequestCard request = requests.create("1");
        newBudgetAfter(request, max);
        crashedMidSend(request, max + 1);

        assertThat(worker.runOnce()).isZero();

        DispatchOperation operation = operation(request);
        assertThat(operation.deliveryStatus()).isEqualTo(DeliveryStatus.UNKNOWN);
        assertThat(Duration.between(START, operation.nextAttemptAt()))
                .isBetween(Duration.ofSeconds(4), Duration.ofSeconds(6));
    }

    private DispatchOperation operation(RequestCard request) {
        return operations.findByRequestId(request.id()).orElseThrow();
    }

    /**
     * {@code attempts} attempts were made, then an operator started a new budget of retries: due now, the attempt
     * number goes on from {@code attempts}.
     */
    private void newBudgetAfter(RequestCard request, int attempts) {
        jdbc.sql("""
                        UPDATE dispatch_operation SET delivery_status = 'UNKNOWN', attempts = :attempts,
                               retry_from = :attempts, next_attempt_at = :now WHERE request_id = :id""")
                .param("attempts", attempts)
                .param("now", toSql(START))
                .param("id", request.id())
                .update();
    }

    /**
     * A worker claimed the operation at {@code START}, opened attempt 1 and died before the carrier answered; the
     * carrier executed it anyway and its result came through the feed, confirming delivery while the lease is live.
     */
    private void crashMidSendThenResultFromFeed(RequestCard request) {
        jdbc.sql("""
                        UPDATE dispatch_operation SET delivery_status = 'SENDING', attempts = 1,
                               locked_until = :lease WHERE request_id = :id""")
                .param("lease", toSql(START.plusSeconds(60)))
                .param("id", request.id())
                .update();
        jdbc.sql("""
                        INSERT INTO exchange_attempt (id, operation_id, attempt_no, started_at)
                        VALUES (:id, :operationId, 1, :started)""")
                .param("id", UUID.randomUUID())
                .param("operationId", request.operationId())
                .param("started", toSql(START))
                .update();
        // The result comes in as the feed poller handles it: received, then processed.
        assertThat(results.deliver(TestFeed.succeeded(1, request.operationId(), "TRK-00000001", "325.00")))
                .isEqualTo("APPLIED");
        assertThat(operation(request).deliveryStatus()).isEqualTo(DeliveryStatus.ACKNOWLEDGED);
    }

    /**
     * A worker claimed the operation as attempt {@code attempt} a minute before {@code START}, opened the attempt and
     * died before recording the outcome: the lease ran out a second ago.
     */
    private void crashedMidSend(RequestCard request, int attempt) {
        jdbc.sql("""
                        UPDATE dispatch_operation SET delivery_status = 'SENDING', attempts = :attempt,
                               locked_until = :expired WHERE request_id = :id""")
                .param("attempt", attempt)
                .param("expired", toSql(START.minusSeconds(1)))
                .param("id", request.id())
                .update();
        jdbc.sql("""
                        INSERT INTO exchange_attempt (id, operation_id, attempt_no, started_at)
                        VALUES (:id, :operationId, :attempt, :started)""")
                .param("id", UUID.randomUUID())
                .param("operationId", request.operationId())
                .param("attempt", attempt)
                .param("started", toSql(START.minusSeconds(61)))
                .update();
    }

    private String attemptError(RequestCard request, int attemptNo) {
        return jdbc.sql("SELECT error FROM exchange_attempt WHERE operation_id = :id AND attempt_no = :no")
                .param("id", request.operationId())
                .param("no", attemptNo)
                .query(String.class)
                .single();
    }

    private List<String> attempts(RequestCard request) {
        return jdbc.sql("""
                        SELECT attempt_no || ':' || coalesce(outcome, '-') || ':' || coalesce(http_status::text, 'null')
                               || ':' || CASE WHEN finished_at IS NULL THEN 'open' ELSE 'finished' END
                          FROM exchange_attempt WHERE operation_id = :id ORDER BY attempt_no""")
                .param("id", request.operationId())
                .query(String.class)
                .list();
    }
}
