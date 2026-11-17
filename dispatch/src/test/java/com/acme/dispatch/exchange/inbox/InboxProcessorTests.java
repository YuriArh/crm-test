package com.acme.dispatch.exchange.inbox;

import static com.acme.dispatch.support.TestClockConfiguration.START;
import static com.acme.dispatch.support.TestFeed.inProgress;
import static com.acme.dispatch.support.TestFeed.succeeded;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.acme.dispatch.request.RequestCard;
import com.acme.dispatch.support.IntegrationTest;
import com.acme.dispatch.support.MutableClock;
import com.acme.dispatch.support.TestRequests;
import com.acme.dispatch.support.TestResults;
import com.acme.dispatch.support.TestRows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Step 2 of result handling: every received record is processed on its own (docs/specs/feed-bad-records.md §4). */
@IntegrationTest
class InboxProcessorTests {

    @Autowired
    InboxProcessor processor;
    @Autowired
    InboxDao dao;   // a spy, see IntegrationTest
    @Autowired
    TestResults results;
    @Autowired
    TestRequests requests;
    @Autowired
    MutableClock clock;
    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        clock.set(START);
    }

    @AfterEach
    void cleanUp() {
        TestRows.deleteRequestData(jdbc);
    }

    @Test
    void receivedRecordWaitsAndIsProcessedOnceByTheNextPass() {
        RequestCard request = requests.create("1");
        UUID id = results.receive(succeeded(1, request.operationId(), "TRK-00000001", "325.00")).orElseThrow();

        // As after a crash between the two steps: received, not processed yet.
        assertThat(results.status(id)).isEqualTo("RECEIVED");
        assertThat(businessStatus(request)).isEqualTo("AWAITING");

        assertThat(processor.processDue()).isEqualTo(1);
        assertThat(results.status(id)).isEqualTo("APPLIED");
        assertThat(processor.processDue()).isZero();
        assertThat(version(request)).isEqualTo(1);
        assertThat(nextAttemptAt(id)).isNull();
    }

    @Test
    void databaseRejectionMakesTheRecordInvalidAndTheNextOneIsApplied() {
        RequestCard rejected = requests.create("1");
        RequestCard next = requests.create("2");
        doThrow(new DataIntegrityViolationException("simulated: numeric field overflow"))
                .doCallRealMethod()
                .when(dao).applyToRequest(any(), any(), any());
        UUID bad = results.receive(succeeded(1, rejected.operationId(), "TRK-00000001", "325.00")).orElseThrow();
        UUID good = results.receive(succeeded(2, next.operationId(), "TRK-00000002", "350.00")).orElseThrow();

        processor.processDue();

        assertThat(results.status(bad)).isEqualTo("INVALID");
        assertThat(reason(bad)).startsWith("Rejected by the database: ").contains("numeric field overflow");
        assertThat(attempts(bad)).as("not a retry").isZero();
        assertThat(businessStatus(rejected)).isEqualTo("AWAITING");
        assertThat(results.status(good)).isEqualTo("APPLIED");
        assertThat(businessStatus(next)).isEqualTo("SUCCEEDED");
    }

    @Test
    void temporaryErrorIsRetriedAfterGrowingPauses() {
        RequestCard request = requests.create("1");
        doThrow(new IllegalStateException("simulated glitch"))
                .doThrow(new IllegalStateException("simulated glitch"))
                .doCallRealMethod()
                .when(dao).applyToRequest(any(), any(), any());
        UUID id = results.receive(inProgress(1, request.operationId())).orElseThrow();

        processor.processDue();
        assertThat(results.status(id)).isEqualTo("RECEIVED");
        assertThat(attempts(id)).isEqualTo(1);
        assertThat(nextAttemptAt(id)).isEqualTo(START.plusSeconds(1));
        assertThat(lastError(id)).contains("simulated glitch");

        assertThat(processor.processDue()).as("not before its time").isZero();
        assertThat(attempts(id)).isEqualTo(1);

        clock.advance(Duration.ofSeconds(1));
        processor.processDue();
        assertThat(attempts(id)).isEqualTo(2);
        assertThat(nextAttemptAt(id)).isEqualTo(START.plusSeconds(1 + 2));

        clock.advance(Duration.ofSeconds(2));
        processor.processDue();
        assertThat(results.status(id)).isEqualTo("APPLIED");
        assertThat(nextAttemptAt(id)).isNull();
        assertThat(businessStatus(request)).isEqualTo("IN_PROGRESS");
    }

    @Test
    void recordThatKeepsFailingBecomesFailedAfterFiveAttemptsWhileOthersGoOn() {
        RequestCard failing = requests.create("1");
        RequestCard other = requests.create("2");
        doAnswer(invocation -> {
            if (failing.id().equals(invocation.getArgument(0))) {
                throw new IllegalStateException("simulated bug");
            }
            return invocation.callRealMethod();
        }).when(dao).applyToRequest(any(), any(), any());
        UUID id = results.receive(inProgress(1, failing.operationId())).orElseThrow();
        UUID otherId = results.receive(inProgress(2, other.operationId())).orElseThrow();

        processor.processDue();
        assertThat(results.status(otherId)).as("the failing record does not hold the others").isEqualTo("APPLIED");

        for (int pass = 2; pass <= 5; pass++) {
            clock.advance(Duration.ofMinutes(1));
            processor.processDue();
        }

        assertThat(results.status(id)).isEqualTo("FAILED");
        assertThat(attempts(id)).isEqualTo(5);
        assertThat(reason(id)).isEqualTo("Processing failed 5 times: java.lang.IllegalStateException: simulated bug");
        assertThat(nextAttemptAt(id)).isNull();
        clock.advance(Duration.ofMinutes(1));
        assertThat(processor.processDue()).as("FAILED is final").isZero();
    }

    @Test
    void failureThatCannotEvenBeRecordedStopsThePassWithoutCountingTheAttempt() {
        RequestCard request = requests.create("1");
        doThrow(new IllegalStateException("simulated: database is gone")).doCallRealMethod()
                .when(dao).applyToRequest(any(), any(), any());
        doThrow(new IllegalStateException("simulated: database is gone")).doCallRealMethod()
                .when(dao).recordRetry(any(), anyInt(), any(), any());
        UUID id = results.receive(inProgress(1, request.operationId())).orElseThrow();

        assertThat(processor.processDue()).isZero();
        assertThat(results.status(id)).isEqualTo("RECEIVED");
        assertThat(attempts(id)).as("the attempt is not counted").isZero();

        assertThat(processor.processDue()).isEqualTo(1);
        assertThat(results.status(id)).isEqualTo("APPLIED");
    }

    @Test
    void parallelProcessorsHandleEveryRecordExactlyOnce() throws Exception {
        List<RequestCard> created = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            RequestCard request = requests.create("1");
            created.add(request);
            results.receive(inProgress(i, request.operationId()));
        }

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> pass = processor::processDue;
            List<Future<Integer>> done = pool.invokeAll(List.of(pass, pass));
            assertThat(done.get(0).get() + done.get(1).get()).isEqualTo(20);
        } finally {
            pool.shutdown();
        }

        assertThat(jdbc.sql("SELECT count(*) FROM inbox WHERE processing_status = 'APPLIED'")
                .query(Integer.class).single()).isEqualTo(20);
        for (RequestCard request : created) {
            assertThat(version(request)).isEqualTo(1);
        }
    }

    @Test
    void appliedRecordIsRemembered() {
        RequestCard request = requests.create("1");
        UUID first = results.receive(inProgress(1, request.operationId())).orElseThrow();
        processor.processDue();

        assertThat(appliedRecord(request)).isEqualTo(first);

        UUID second = results.receive(succeeded(2, request.operationId(), "TRK-00000001", "325.00")).orElseThrow();
        processor.processDue();

        assertThat(appliedRecord(request)).isEqualTo(second);
    }

    @Test
    void sameSequenceIsComparedWithTheAppliedRecord() {
        RequestCard request = requests.create("1");
        UUID operationId = request.operationId();
        assertThat(results.deliver(succeeded(1, operationId, "TRK-00000001", "325.00"))).isEqualTo("APPLIED");
        UUID disputed = results.receive(succeeded(2, operationId, "TRK-00000002", "400.00")).orElseThrow();
        processor.processDue();
        assertThat(results.status(disputed)).isEqualTo("CONFLICT");
        // As an operator's decision does: the disputed record is now the applied one.
        jdbc.sql("UPDATE dispatch_operation SET applied_inbox_id = :id WHERE request_id = :request")
                .param("id", disputed)
                .param("request", request.id())
                .update();

        assertThat(results.deliver(succeeded(3, operationId, "TRK-00000002", "400.00")))
                .as("the applied version again").isEqualTo("STALE");
        assertThat(results.deliver(succeeded(4, operationId, "TRK-00000001", "325.00")))
                .as("the version the operator did not choose").isEqualTo("CONFLICT");
    }

    @Test
    void processedRecordHasProcessedAt() {
        RequestCard request = requests.create("1");
        UUID operationId = request.operationId();
        UUID applied = results.receive(succeeded(1, operationId, "TRK-00000001", "325.00")).orElseThrow();
        UUID stale = results.receive(inProgress(2, operationId)).orElseThrow();
        UUID conflict = results.receive(succeeded(3, operationId, "TRK-00000002", "400.00")).orElseThrow();
        UUID invalid = results.receive(succeeded(4, UUID.randomUUID(), "TRK-00000003", "1.00")).orElseThrow();
        Instant processing = START.plusSeconds(30);
        clock.set(processing);

        processor.processDue();

        assertThat(List.of(results.status(applied), results.status(stale), results.status(conflict),
                results.status(invalid))).containsExactly("APPLIED", "STALE", "CONFLICT", "INVALID");
        assertThat(List.of(results.processedAt(applied), results.processedAt(stale), results.processedAt(conflict),
                results.processedAt(invalid))).containsOnly(processing);
    }

    @Test
    void failedRecordHasProcessedAt() {
        RequestCard request = requests.create("1");
        doThrow(new IllegalStateException("simulated bug")).when(dao).applyToRequest(any(), any(), any());
        UUID id = results.receive(inProgress(1, request.operationId())).orElseThrow();

        processor.processDue();
        for (int pass = 2; pass <= 5; pass++) {
            clock.advance(Duration.ofMinutes(1));
            processor.processDue();
        }

        assertThat(results.status(id)).isEqualTo("FAILED");
        assertThat(results.processedAt(id)).isEqualTo(START.plus(Duration.ofMinutes(4)));
    }

    @Test
    void retriedRecordIsNotProcessedYet() {
        RequestCard request = requests.create("1");
        doThrow(new IllegalStateException("simulated glitch")).doCallRealMethod()
                .when(dao).applyToRequest(any(), any(), any());
        UUID id = results.receive(inProgress(1, request.operationId())).orElseThrow();

        processor.processDue();

        assertThat(results.status(id)).isEqualTo("RECEIVED");
        assertThat(results.processedAt(id)).isNull();
    }

    private UUID appliedRecord(RequestCard request) {
        return jdbc.sql("SELECT applied_inbox_id FROM dispatch_operation WHERE request_id = :id")
                .param("id", request.id())
                .query(UUID.class)
                .single();
    }

    private String businessStatus(RequestCard request) {
        return jdbc.sql("SELECT business_status FROM delivery_request WHERE id = :id")
                .param("id", request.id()).query(String.class).single();
    }

    private long version(RequestCard request) {
        return jdbc.sql("SELECT version FROM delivery_request WHERE id = :id")
                .param("id", request.id()).query(Long.class).single();
    }

    private int attempts(UUID id) {
        return jdbc.sql("SELECT attempts FROM inbox WHERE id = :id").param("id", id).query(Integer.class).single();
    }

    private String reason(UUID id) {
        return jdbc.sql("SELECT reason FROM inbox WHERE id = :id").param("id", id).query(String.class).single();
    }

    private String lastError(UUID id) {
        return jdbc.sql("SELECT last_error FROM inbox WHERE id = :id").param("id", id).query(String.class).single();
    }

    private Instant nextAttemptAt(UUID id) {
        OffsetDateTime value = jdbc.sql("SELECT next_attempt_at FROM inbox WHERE id = :id").param("id", id)
                .query((rs, n) -> rs.getObject(1, OffsetDateTime.class)).list().getFirst();
        return value == null ? null : value.toInstant();
    }
}
