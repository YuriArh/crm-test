package com.acme.dispatch.exchange.reconcile;

import static com.acme.dispatch.support.TestClockConfiguration.START;
import static com.acme.dispatch.support.TestFeed.atFeedSeq;
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
import com.acme.dispatch.support.TestRequests;
import com.acme.dispatch.support.TestResults;
import com.acme.dispatch.support.TestRows;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * EXTERNAL_AHEAD: the carrier's result is taken over through the result handler, in the transaction of the check
 * ({@code docs/specs/reconciliation.md} §2.3, §2.5); an applied result closes EXTERNAL_MISSING (§2.8).
 */
@IntegrationTest
class ReconcileAheadTests {

    static final SubmitResult PERMANENT = new SubmitResult(SubmitOutcome.PERMANENT_ERROR, 400, "HTTP 400");

    @Autowired
    Reconciler reconciler;
    @Autowired
    SendWorker worker;
    @Autowired
    InboxProcessor processor;
    @Autowired
    FakeCarrier carrier;
    @Autowired
    TestRequests requests;
    @Autowired
    TestResults results;
    @Autowired
    MutableClock clock;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    TransactionTemplate transaction;
    @Autowired
    InboxDao inboxDao;   // a spy, see IntegrationTest

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
    void failedExecutedAppliesResult() {
        RequestCard request = requests.create("1");
        carrier.answer(PERMANENT);
        worker.runOnce();
        carrier.lookupAnswer(request.operationId(), FakeCarrier.executed(request.operationId(),
                succeeded(1, request.operationId(), "TRK-00000001", "612.50")));

        reconciler.check(request.id(), RunType.SCHEDULED, null);

        assertThat(result(request)).as("applied without a processing pass").isEqualTo("SUCCEEDED/TRK-00000001/1");
        assertThat(records(request)).containsExactly("RECONCILIATION:APPLIED");
        assertThat(jdbc.sql("""
                        SELECT r.outcome || ':' || r.acknowledged || ':' || (r.inbox_id = o.applied_inbox_id)
                          FROM reconciliation r JOIN dispatch_operation o ON o.request_id = r.request_id
                         WHERE r.request_id = :id""")
                .param("id", request.id()).query(String.class).single()).isEqualTo("EXTERNAL_AHEAD:true:true");
        assertThat(problems(request)).containsExactly("DELIVERY_FAILED:RESOLVED:SYSTEM");
        assertThat(jdbc.sql("""
                        SELECT coalesce(local_result, '-') || coalesce(local_status, '-')
                               || coalesce(local_tracking_number, '-') || coalesce(local_price::text, '-')
                               || coalesce(local_reason, '-')
                          FROM reconciliation WHERE request_id = :id""")
                .param("id", request.id()).query(String.class).single())
                .as("we had no result before the check").isEqualTo("-----");
    }

    @Test
    void invalidFeedRecordThenReconciliation() {
        RequestCard request = acknowledged();
        JsonNode good = succeeded(1, request.operationId(), "TRK-00000001", "612.50");
        ObjectNode broken = ((ObjectNode) good).deepCopy();
        broken.put("price", "612,50");
        assertThat(results.deliver(broken)).isEqualTo("INVALID");
        carrier.lookupAnswer(request.operationId(), FakeCarrier.executed(request.operationId(), good));

        reconciler.check(request.id(), RunType.SCHEDULED, null);
        assertThat(result(request)).isEqualTo("SUCCEEDED/TRK-00000001/1");

        assertThat(results.deliver(atFeedSeq(good, 2))).as("the same message from the feed again").isEqualTo("DUPLICATE");
        assertThat(result(request)).isEqualTo("SUCCEEDED/TRK-00000001/1");
    }

    /** The feed brings the message for the first time after reconciliation took it over (a delayed result). */
    @Test
    void delayedFeedRecordAfterReconciliationIsStale() {
        RequestCard request = acknowledged();
        JsonNode result = succeeded(1, request.operationId(), "TRK-00000001", "612.50");
        carrier.lookupAnswer(request.operationId(), FakeCarrier.executed(request.operationId(), result));
        reconciler.check(request.id(), RunType.SCHEDULED, null);

        assertThat(results.deliver(result)).isEqualTo("STALE");

        assertThat(result(request)).isEqualTo("SUCCEEDED/TRK-00000001/1");
        assertThat(problems(request)).isEmpty();
    }

    @Test
    void processingFailureOfAheadRecordIsRetriedLater() {
        RequestCard request = acknowledged();
        carrier.lookupAnswer(request.operationId(), FakeCarrier.executed(request.operationId(),
                succeeded(1, request.operationId(), "TRK-00000001", "612.50")));
        doThrow(new IllegalStateException("disk full")).when(inboxDao).applyToRequest(any(), any(), any());

        reconciler.check(request.id(), RunType.SCHEDULED, null);

        assertThat(records(request)).as("the record waits for its next attempt").containsExactly("RECONCILIATION:RECEIVED");
        assertThat(jdbc.sql("SELECT outcome || ':' || (inbox_id IS NOT NULL) FROM reconciliation WHERE request_id = :id")
                .param("id", request.id()).query(String.class).single()).isEqualTo("EXTERNAL_AHEAD:true");
        assertThat(result(request)).isEqualTo("AWAITING/-/0");

        org.mockito.Mockito.reset(inboxDao);
        clock.advance(Duration.ofSeconds(2));
        processor.processDue();
        assertThat(result(request)).isEqualTo("SUCCEEDED/TRK-00000001/1");
    }

    @Test
    void repeatedAheadUsesExistingRecord() {
        RequestCard request = acknowledged();
        carrier.lookupAnswer(request.operationId(), FakeCarrier.executed(request.operationId(),
                succeeded(1, request.operationId(), "TRK-00000001", "612.50")));
        doThrow(new IllegalStateException("disk full")).when(inboxDao).applyToRequest(any(), any(), any());
        reconciler.check(request.id(), RunType.SCHEDULED, null);
        org.mockito.Mockito.reset(inboxDao);
        clock.advance(Duration.ofMinutes(5));

        reconciler.check(request.id(), RunType.SCHEDULED, null);

        assertThat(records(request)).containsExactly("RECONCILIATION:APPLIED");
        assertThat(result(request)).isEqualTo("SUCCEEDED/TRK-00000001/1");
        assertThat(jdbc.sql("SELECT count(DISTINCT inbox_id) FROM reconciliation WHERE request_id = :id")
                .param("id", request.id()).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                        SELECT details FROM reconciliation WHERE request_id = :id ORDER BY checked_at DESC, id DESC
                         LIMIT 1""")
                .param("id", request.id()).query(String.class).single()).contains("already received");
    }

    @Test
    void raceWithFeed() {
        RequestCard request = acknowledged();
        JsonNode result = succeeded(1, request.operationId(), "TRK-00000001", "612.50");
        carrier.lookupAnswer(request.operationId(), FakeCarrier.executed(request.operationId(), result));
        carrier.duringLookup(() -> results.deliver(result));

        reconciler.check(request.id(), RunType.SCHEDULED, null);

        assertThat(jdbc.sql("SELECT outcome FROM reconciliation WHERE request_id = :id")
                .param("id", request.id()).query(String.class).single()).isEqualTo("MATCH");
        assertThat(records(request)).containsExactly("FEED:APPLIED");
        assertThat(result(request)).isEqualTo("SUCCEEDED/TRK-00000001/1");
    }

    @Test
    void externalMissingClosedByAppliedFeedResult() {
        RequestCard request = acknowledged();
        reconciler.check(request.id(), RunType.SCHEDULED, null);
        assertThat(problems(request)).containsExactly("EXTERNAL_MISSING:OPEN:-");

        results.deliver(succeeded(1, request.operationId(), "TRK-00000001", "612.50"));

        assertThat(problems(request)).containsExactly("EXTERNAL_MISSING:RESOLVED:SYSTEM");
    }

    @Test
    void processSkipsAlreadyDecidedRecord() {
        RequestCard request = acknowledged();
        UUID record = results.receive(succeeded(1, request.operationId(), "TRK-00000001", "612.50")).orElseThrow();
        processor.processDue();

        Boolean processed = transaction.execute(status -> processor.process(record));

        assertThat(processed).isFalse();
        assertThat(results.status(record)).isEqualTo("APPLIED");
        assertThat(result(request)).isEqualTo("SUCCEEDED/TRK-00000001/1");
    }

    private RequestCard acknowledged() {
        RequestCard request = requests.create("1");
        worker.runOnce();
        return request;
    }

    private String result(RequestCard request) {
        return jdbc.sql("""
                        SELECT business_status || '/' || coalesce(tracking_number, '-') || '/' || version
                          FROM delivery_request WHERE id = :id""")
                .param("id", request.id()).query(String.class).single();
    }

    private List<String> records(RequestCard request) {
        return jdbc.sql("""
                        SELECT source || ':' || processing_status FROM inbox
                         WHERE payload LIKE '%' || :operation || '%' AND processing_status <> 'INVALID'
                         ORDER BY received_at, id""")
                .param("operation", request.operationId().toString()).query(String.class).list();
    }

    private List<String> problems(RequestCard request) {
        return jdbc.sql("""
                        SELECT kind || ':' || status || ':' || coalesce(decision, '-')
                          FROM problem WHERE request_id = :id ORDER BY opened_at, kind""")
                .param("id", request.id()).query(String.class).list();
    }
}
