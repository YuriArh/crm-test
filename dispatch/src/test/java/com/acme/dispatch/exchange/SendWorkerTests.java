package com.acme.dispatch.exchange;

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
import com.acme.dispatch.support.TestRequests;
import com.acme.dispatch.support.TestRows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@IntegrationTest
class SendWorkerTests {

    static final SubmitResult TEMPORARY = new SubmitResult(SubmitOutcome.TEMPORARY_ERROR, 503, "HTTP 503");
    static final SubmitResult UNKNOWN = new SubmitResult(SubmitOutcome.UNKNOWN, null, "No response in time");
    static final SubmitResult PERMANENT = new SubmitResult(SubmitOutcome.PERMANENT_ERROR, 400, "HTTP 400");
    static final SubmitResult CONFLICT = new SubmitResult(SubmitOutcome.PAYLOAD_CONFLICT, 409, "HTTP 409");

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
    void batchIsSentInDueOrder() {
        List<UUID> created = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            created.add(requests.create(String.valueOf(i)).operationId());
            clock.advance(Duration.ofSeconds(1));
        }

        assertThat(worker.runOnce()).isEqualTo(6);

        // UPDATE … RETURNING has no order of its own; the claim must still hand the batch over oldest first.
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
                .param("expired", START.minusSeconds(1).atOffset(java.time.ZoneOffset.UTC))
                .param("id", request.id())
                .update();
        jdbc.sql("""
                        INSERT INTO exchange_attempt (id, operation_id, attempt_no, started_at)
                        VALUES (:id, :operationId, 1, :started)""")
                .param("id", UUID.randomUUID())
                .param("operationId", request.operationId())
                .param("started", START.minusSeconds(61).atOffset(java.time.ZoneOffset.UTC))
                .update();

        assertThat(worker.runOnce()).isEqualTo(1);

        DispatchOperation operation = operation(request);
        assertThat(operation.deliveryStatus()).isEqualTo(DeliveryStatus.ACKNOWLEDGED);
        assertThat(operation.attempts()).isEqualTo(2);
        assertThat(attempts(request)).containsExactly("1:UNKNOWN:null:finished", "2:ACKNOWLEDGED:202:finished");
    }

    @Test
    void liveLeaseIsNotTakenOver() {
        RequestCard request = requests.create("1");
        jdbc.sql("""
                        UPDATE dispatch_operation SET delivery_status = 'SENDING', attempts = 1,
                               locked_until = :lease WHERE request_id = :id""")
                .param("lease", START.plusSeconds(30).atOffset(java.time.ZoneOffset.UTC))
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
