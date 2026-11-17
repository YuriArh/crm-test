package com.acme.dispatch.problem;

import static com.acme.dispatch.support.TestClockConfiguration.START;
import static com.acme.dispatch.support.TestFeed.entry;
import static com.acme.dispatch.support.TestFeed.inProgress;
import static com.acme.dispatch.support.TestFeed.succeeded;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.acme.dispatch.carrier.SubmitOutcome;
import com.acme.dispatch.carrier.SubmitResult;
import com.acme.dispatch.exchange.SendWorker;
import com.acme.dispatch.exchange.inbox.InboxDao;
import com.acme.dispatch.exchange.inbox.InboxProcessor;
import com.acme.dispatch.request.RequestCard;
import com.acme.dispatch.support.FakeCarrier;
import com.acme.dispatch.support.IntegrationTest;
import com.acme.dispatch.support.MutableClock;
import com.acme.dispatch.support.OurSide;
import com.acme.dispatch.support.TestFeed;
import com.acme.dispatch.support.TestRequests;
import com.acme.dispatch.support.TestResults;
import com.acme.dispatch.support.TestRows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Problems of received records are opened after the record's savepoint, in its transaction (operator-problems.md
 * §2.3): a failure of ours is never blamed on the carrier.
 */
@IntegrationTest
class MessageProblemsTests {

    @Autowired
    TestResults results;
    @Autowired
    TestRequests requests;
    @Autowired
    InboxProcessor processor;
    @Autowired
    SendWorker worker;
    @Autowired
    FakeCarrier carrier;
    @Autowired
    MutableClock clock;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    InboxDao inboxDao;   // a spy, see IntegrationTest
    @Autowired
    ProblemDao problemDao;   // a spy, see IntegrationTest

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
    void invalidRecordOfAKnownRequestOpensProblemWithIt() {
        RequestCard request = requests.create("1");
        UUID record = results.receive(entry(1, UUID.randomUUID().toString(), request.operationId(), 2, "SUCCEEDED",
                "TRK-00000001", "-1.00", null)).orElseThrow();
        processor.processDue();

        assertThat(results.status(record)).isEqualTo("INVALID");
        assertThat(problemOf(record)).startsWith("INVALID_MESSAGE:OPEN:" + request.id() + ":");
    }

    @Test
    void invalidRecordOfAnUnknownOperationHasNoRequest() {
        UUID record = results.receive(succeeded(1, UUID.randomUUID(), "TRK-00000001", "325.00")).orElseThrow();
        processor.processDue();

        assertThat(problemOf(record)).startsWith("INVALID_MESSAGE:OPEN:-:Unknown operationId");
    }

    @Test
    void unreadableRecordHasNoRequest() {
        UUID record = results.receive(TestFeed.raw("{\"feedSeq\": 1, \"status\": 7}")).orElseThrow();
        processor.processDue();

        assertThat(problemOf(record)).startsWith("INVALID_MESSAGE:OPEN:-:messageId is missing or malformed");
    }

    @Test
    void failedRecordOpensMessageFailed() {
        RequestCard request = requests.create("1");
        doThrow(new IllegalStateException("simulated bug")).when(inboxDao).applyToRequest(any(), any(), any(), any());
        UUID record = results.receive(inProgress(1, request.operationId())).orElseThrow();

        for (int pass = 1; pass <= 5; pass++) {
            processor.processDue();
            clock.advance(Duration.ofMinutes(1));
        }

        assertThat(results.status(record)).isEqualTo("FAILED");
        assertThat(problemOf(record)).isEqualTo("MESSAGE_FAILED:OPEN:" + request.id()
                + ":Processing failed 5 times: java.lang.IllegalStateException: simulated bug");
    }

    @Test
    void conflictOpensResultConflict() {
        RequestCard request = requests.create("1");
        assertThat(results.deliver(succeeded(1, request.operationId(), "TRK-00000001", "325.00"))).isEqualTo("APPLIED");
        UUID record = results.receive(succeeded(2, request.operationId(), "TRK-00000002", "400.00")).orElseThrow();
        processor.processDue();

        assertThat(results.status(record)).isEqualTo("CONFLICT");
        assertThat(problemOf(record)).isEqualTo("RESULT_CONFLICT:OPEN:" + request.id()
                + ":Sequence 2 was already applied with different content");
    }

    @Test
    void resultConflictRecordsOurResult() {
        RequestCard request = requests.create("1");
        results.deliver(succeeded(1, request.operationId(), "TRK-00000001", "325.00"));
        UUID record = results.receive(succeeded(2, request.operationId(), "TRK-00000002", "400.00")).orElseThrow();
        processor.processDue();

        assertThat(OurSide.of(jdbc, problemIdOf(record))).isEqualTo("SUCCEEDED/TRK-00000001/325.00/-");
    }

    @Test
    void messageProblemsHaveNoOurSide() {
        RequestCard request = requests.create("1");
        UUID known = results.receive(entry(1, UUID.randomUUID().toString(), request.operationId(), 2, "SUCCEEDED",
                "TRK-00000001", "-1.00", null)).orElseThrow();
        UUID unknown = results.receive(succeeded(2, UUID.randomUUID(), "TRK-00000001", "325.00")).orElseThrow();
        processor.processDue();

        assertThat(OurSide.of(jdbc, problemIdOf(known))).isEqualTo(OurSide.NOT_RECORDED);
        assertThat(OurSide.of(jdbc, problemIdOf(unknown))).isEqualTo(OurSide.NOT_RECORDED);
    }

    @Test
    void duplicateOpensNothing() {
        RequestCard request = requests.create("1");
        var invalid = entry(1, "m-1", request.operationId(), 2, "SUCCEEDED", "TRK-00000001", "-1.00", null);
        results.deliver(invalid);

        assertThat(results.deliver(TestFeed.atFeedSeq(invalid, 2))).isEqualTo("DUPLICATE");
        assertThat(problemCount()).isEqualTo(1);
    }

    @Test
    void appliedAndStaleOpenNothing() {
        RequestCard request = requests.create("1");
        var result = succeeded(1, request.operationId(), "TRK-00000001", "325.00");
        results.deliver(result);
        results.deliver(entry(2, UUID.randomUUID().toString(), request.operationId(), 1, "IN_PROGRESS", null, null,
                null));

        assertThat(problemCount()).isZero();
    }

    @Test
    void resultConfirmingDeliveryClosesDeliveryProblems() {
        RequestCard request = requests.create("1");
        carrier.answer(new SubmitResult(SubmitOutcome.PERMANENT_ERROR, 400, "HTTP 400"));
        worker.runOnce();
        assertThat(deliveryProblems(request)).containsExactly("DELIVERY_FAILED:OPEN");

        results.deliver(succeeded(1, request.operationId(), "TRK-00000001", "325.00"));

        assertThat(deliveryProblems(request)).containsExactly("DELIVERY_FAILED:RESOLVED");
    }

    @Test
    void refusedProblemDoesNotBlameTheCarrier() {
        RequestCard request = requests.create("1");
        results.deliver(succeeded(1, request.operationId(), "TRK-00000001", "325.00"));
        doThrow(new DataIntegrityViolationException("simulated: problem refused"))
                .when(problemDao).open(any(), any(), any(), any(), any(), any());
        UUID record = results.receive(succeeded(2, request.operationId(), "TRK-00000002", "400.00")).orElseThrow();

        assertThat(processor.processDue()).as("the pass stops").isZero();

        assertThat(results.status(record)).as("not INVALID: the carrier's data is fine").isEqualTo("RECEIVED");
        assertThat(problemCount()).isZero();
    }

    @Test
    void refusedClosingDoesNotApplyTheResult() {
        RequestCard request = requests.create("1");
        carrier.answer(new SubmitResult(SubmitOutcome.PERMANENT_ERROR, 400, "HTTP 400"));
        worker.runOnce();
        doThrow(new DataIntegrityViolationException("simulated: problem refused"))
                .when(problemDao).closeBySystem(any(), any(), any());
        UUID record = results.receive(succeeded(1, request.operationId(), "TRK-00000001", "325.00")).orElseThrow();

        assertThat(processor.processDue()).isZero();

        assertThat(results.status(record)).isEqualTo("RECEIVED");
        assertThat(jdbc.sql("SELECT business_status FROM delivery_request WHERE id = :id")
                .param("id", request.id()).query(String.class).single()).isEqualTo("AWAITING");
        assertThat(deliveryProblems(request)).containsExactly("DELIVERY_FAILED:OPEN");
    }

    private String problemOf(UUID record) {
        return jdbc.sql("""
                        SELECT kind || ':' || status || ':' || coalesce(request_id::text, '-') || ':' || details
                          FROM problem WHERE inbox_id = :id""")
                .param("id", record)
                .query(String.class)
                .single();
    }

    private UUID problemIdOf(UUID record) {
        return jdbc.sql("SELECT id FROM problem WHERE inbox_id = :id").param("id", record).query(UUID.class).single();
    }

    private List<String> deliveryProblems(RequestCard request) {
        return jdbc.sql("SELECT kind || ':' || status FROM problem WHERE request_id = :id AND inbox_id IS NULL")
                .param("id", request.id())
                .query(String.class)
                .list();
    }

    private long problemCount() {
        return jdbc.sql("SELECT count(*) FROM problem").query(Long.class).single();
    }
}
