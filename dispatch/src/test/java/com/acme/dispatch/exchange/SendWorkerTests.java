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
import com.acme.dispatch.exchange.inbox.InboxSource;
import com.acme.dispatch.exchange.inbox.ResultInbox;
import com.acme.dispatch.request.ExchangeInfo;
import com.acme.dispatch.request.RequestCard;
import com.acme.dispatch.request.RequestQueryService;
import com.acme.dispatch.support.DemoData;
import com.acme.dispatch.support.FakeCarrier;
import com.acme.dispatch.support.IntegrationTest;
import com.acme.dispatch.support.MutableClock;
import com.acme.dispatch.support.TestFeed;
import com.acme.dispatch.support.TestRequests;
import com.acme.dispatch.support.TestRows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
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
    ResultInbox inbox;
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
    void sendInterruptedByACrashIsRecoveredAsUnknownAndResent() {
        RequestCard request = requests.create("1");
        // A worker claimed it a minute ago and died before recording the outcome.
        jdbc.sql("""
                        UPDATE dispatch_operation SET delivery_status = 'SENDING', attempts = 1,
                               locked_until = :expired WHERE request_id = :id""")
                .param("expired", toSql(START.minusSeconds(1)))
                .param("id", request.id())
                .update();
        jdbc.sql("""
                        INSERT INTO exchange_attempt (id, operation_id, attempt_no, started_at)
                        VALUES (:id, :operationId, 1, :started)""")
                .param("id", UUID.randomUUID())
                .param("operationId", request.operationId())
                .param("started", toSql(START.minusSeconds(61)))
                .update();

        assertThat(worker.runOnce()).isEqualTo(1);

        DispatchOperation operation = operation(request);
        assertThat(operation.deliveryStatus()).isEqualTo(DeliveryStatus.ACKNOWLEDGED);
        assertThat(operation.attempts()).isEqualTo(2);
        assertThat(attempts(request)).containsExactly("1:UNKNOWN:null:finished", "2:ACKNOWLEDGED:202:finished");
        assertThat(attemptError(request, 1)).isEqualTo(INTERRUPTED);
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
                assertThat(worker.runOnce()).as("the other instance resends").isEqualTo(1);
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

    private DispatchOperation operation(RequestCard request) {
        return operations.findByRequestId(request.id()).orElseThrow();
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
        // The single result handler, as the feed poller calls it (inside its transaction).
        ResultInbox.Outcome outcome = transaction.execute(status -> inbox.accept(
                TestFeed.succeeded(1, request.operationId(), "TRK-1", "325.00"), InboxSource.FEED));
        assertThat(outcome).isEqualTo(ResultInbox.Outcome.APPLIED);
        assertThat(operation(request).deliveryStatus()).isEqualTo(DeliveryStatus.ACKNOWLEDGED);
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
