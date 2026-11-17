package com.acme.dispatch.exchange.reconcile;

import static com.acme.dispatch.support.TestClockConfiguration.START;
import static com.acme.dispatch.support.TestFeed.succeeded;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import com.acme.dispatch.carrier.LookupResult;
import com.acme.dispatch.carrier.SubmitOutcome;
import com.acme.dispatch.carrier.SubmitResult;
import com.acme.dispatch.exchange.SendWorker;
import com.acme.dispatch.problem.ProblemDao;
import com.acme.dispatch.request.RequestCard;
import com.acme.dispatch.support.FakeCarrier;
import com.acme.dispatch.support.IntegrationTest;
import com.acme.dispatch.support.MutableClock;
import com.acme.dispatch.support.TestRequests;
import com.acme.dispatch.support.TestResults;
import com.acme.dispatch.support.TestRows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * One reconciliation check and what it does to delivery ({@code docs/specs/reconciliation.md} §2.3–2.5): the carrier
 * knowing the operation confirms delivery, not knowing it is EXTERNAL_MISSING, a broken or missing answer is ERROR.
 */
@IntegrationTest
class ReconcileDeliveryTests {

    static final SubmitResult TIMEOUT = new SubmitResult(SubmitOutcome.UNKNOWN, null, "No response in time");
    static final SubmitResult PERMANENT = new SubmitResult(SubmitOutcome.PERMANENT_ERROR, 400, "HTTP 400");

    @Autowired
    Reconciler reconciler;
    @Autowired
    SendWorker worker;
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
    void unknownAcceptedIsAcknowledged() {
        RequestCard request = requests.create("1");
        carrier.answer(TIMEOUT);
        worker.runOnce();
        carrier.lookupAnswer(request.operationId(), FakeCarrier.accepted(request.operationId()));

        reconciler.check(request.id(), RunType.SCHEDULED, null);

        assertThat(delivery(request)).isEqualTo("ACKNOWLEDGED/RECONCILIATION");
        assertThat(problems(request)).containsExactly("DELIVERY_UNKNOWN:RESOLVED:SYSTEM");
        assertThat(rows(request)).containsExactly("MATCH:true:UNKNOWN:ACCEPTED");
        clock.advance(Duration.ofHours(1));
        worker.runOnce();
        assertThat(carrier.submitted()).as("no resend after the confirmation").hasSize(1);
    }

    @Test
    void failedAcceptedIsAcknowledged() {
        RequestCard request = requests.create("1");
        carrier.answer(PERMANENT);
        worker.runOnce();
        carrier.lookupAnswer(request.operationId(), FakeCarrier.accepted(request.operationId()));

        reconciler.check(request.id(), RunType.SCHEDULED, null);

        assertThat(delivery(request)).isEqualTo("ACKNOWLEDGED/RECONCILIATION");
        assertThat(problems(request)).containsExactly("DELIVERY_FAILED:RESOLVED:SYSTEM");
    }

    @Test
    void unknownNotFoundIsSentNow() {
        RequestCard request = requests.create("1");
        carrier.answer(TIMEOUT);
        worker.runOnce();
        clock.advance(Duration.ofSeconds(1));

        reconciler.check(request.id(), RunType.SCHEDULED, null);

        assertThat(rows(request)).containsExactly("EXTERNAL_MISSING:false:UNKNOWN:NOT_FOUND");
        assertThat(jdbc.sql("SELECT next_attempt_at, attempts, retry_from FROM dispatch_operation WHERE request_id = :id")
                .param("id", request.id())
                .query((rs, n) -> rs.getObject(1, OffsetDateTime.class).toInstant() + "/" + rs.getInt(2) + "/"
                        + rs.getInt(3))
                .single()).isEqualTo(clock.instant() + "/1/0");
        assertThat(problems(request)).as("no problem: the resend clears it up").containsExactly("DELIVERY_UNKNOWN:OPEN:-");
        worker.runOnce();
        assertThat(carrier.submitted()).hasSize(2);
        assertThat(carrier.submitted().get(1).operationId()).isEqualTo(request.operationId());
    }

    @Test
    void failedNotFoundChangesNothing() {
        RequestCard request = requests.create("1");
        carrier.answer(PERMANENT);
        worker.runOnce();

        reconciler.check(request.id(), RunType.SCHEDULED, null);

        assertThat(rows(request)).containsExactly("EXTERNAL_MISSING:false:FAILED:NOT_FOUND");
        assertThat(delivery(request)).isEqualTo("FAILED/-");
        assertThat(problems(request)).containsExactly("DELIVERY_FAILED:OPEN:-");
    }

    @Test
    void acknowledgedNotFoundOpensExternalMissing() {
        RequestCard request = acknowledged();

        reconciler.check(request.id(), RunType.SCHEDULED, null);
        clock.advance(Duration.ofMinutes(5));
        reconciler.check(request.id(), RunType.SCHEDULED, null);

        assertThat(rows(request)).containsExactly("EXTERNAL_MISSING:false:ACKNOWLEDGED:NOT_FOUND",
                "EXTERNAL_MISSING:false:ACKNOWLEDGED:NOT_FOUND");
        assertThat(problems(request)).containsExactly("EXTERNAL_MISSING:OPEN:-");
        assertThat(jdbc.sql("SELECT details FROM problem WHERE request_id = :id").param("id", request.id())
                .query(String.class).single()).contains("OPERATION_NOT_FOUND");
    }

    @Test
    void externalMissingClosedWhenCarrierKnowsAgain() {
        RequestCard request = acknowledged();
        reconciler.check(request.id(), RunType.SCHEDULED, null);
        carrier.lookupAnswer(request.operationId(), FakeCarrier.accepted(request.operationId()));
        clock.advance(Duration.ofMinutes(5));

        reconciler.check(request.id(), RunType.SCHEDULED, null);

        assertThat(problems(request)).containsExactly("EXTERNAL_MISSING:RESOLVED:SYSTEM");
        assertThat(rows(request)).containsExactly("EXTERNAL_MISSING:false:ACKNOWLEDGED:NOT_FOUND",
                "MATCH:false:ACKNOWLEDGED:ACCEPTED");
    }

    @Test
    void pendingNotFoundIsMatch() {
        RequestCard request = requests.create("1");

        reconciler.check(request.id(), RunType.SCHEDULED, null);

        assertThat(rows(request)).containsExactly("MATCH:false:PENDING:NOT_FOUND");
        assertThat(problems(request)).isEmpty();
    }

    @Test
    void unavailableIsErrorWithBasePause() {
        RequestCard request = acknowledged();
        carrier.lookupUnavailable(true);

        reconciler.check(request.id(), RunType.SCHEDULED, null);
        assertThat(times(request)).isEqualTo(clock.instant() + "/" + clock.instant().plus(Duration.ofMinutes(5)));
        clock.advance(Duration.ofMinutes(5));
        reconciler.check(request.id(), RunType.SCHEDULED, null);

        assertThat(rows(request)).containsExactly("ERROR:false:ACKNOWLEDGED:-", "ERROR:false:ACKNOWLEDGED:-");
        assertThat(times(request)).isEqualTo(clock.instant() + "/" + clock.instant().plus(Duration.ofMinutes(5)));
        assertThat(jdbc.sql("SELECT details FROM reconciliation WHERE request_id = :id LIMIT 1")
                .param("id", request.id()).query(String.class).single()).contains("unavailable");
        assertThat(delivery(request)).isEqualTo("ACKNOWLEDGED/RESPONSE");
    }

    @Test
    void rowKeepsOurSide() {
        RequestCard request = acknowledged();
        results.deliver(succeeded(1, request.operationId(), "TRK-00000001", "612.50"));
        carrier.lookupAnswer(request.operationId(), FakeCarrier.executed(request.operationId(),
                succeeded(1, request.operationId(), "TRK-00000001", "612.5")));

        reconciler.check(request.id(), RunType.SCHEDULED, null);

        assertThat(jdbc.sql("""
                        SELECT outcome || ':' || delivery_status || ':' || local_result || ':' || carrier_result_status
                               || ':' || carrier_tracking_number || ':' || carrier_price || ':' || carrier_sequence
                          FROM reconciliation WHERE request_id = :id""")
                .param("id", request.id()).query(String.class).single())
                .isEqualTo("MATCH:ACKNOWLEDGED:SUCCEEDED|TRK-00000001|612.5|:SUCCEEDED:TRK-00000001:612.50:2");
    }

    @Test
    void invalidAnswerIsErrorWithDetails() {
        RequestCard request = acknowledged();
        String raw = "{\"code\":\"VALIDATION_FAILED\",\"message\":\"bad id\"}";
        carrier.lookupAnswer(request.operationId(),
                new LookupResult.Invalid("Unexpected HTTP 400", "VALIDATION_FAILED", "bad id", raw));

        reconciler.check(request.id(), RunType.SCHEDULED, null);

        assertThat(rows(request)).containsExactly("ERROR:false:ACKNOWLEDGED:-");
        assertThat(jdbc.sql("SELECT details || '#' || carrier_response FROM reconciliation WHERE request_id = :id")
                .param("id", request.id()).query(String.class).single())
                .contains("Unexpected HTTP 400").contains("VALIDATION_FAILED: bad id").endsWith("#" + raw);
        assertThat(problems(request)).isEmpty();
    }

    @Test
    void sendingAcknowledgedBeatsWorker() {
        RequestCard request = requests.create("1");
        carrier.lookupAnswer(request.operationId(), FakeCarrier.accepted(request.operationId()));
        carrier.answer(TIMEOUT);
        carrier.duringSubmit(() -> reconciler.check(request.id(), RunType.SCHEDULED, null));

        worker.runOnce();

        assertThat(rows(request)).containsExactly("MATCH:true:SENDING:ACCEPTED");
        assertThat(delivery(request)).isEqualTo("ACKNOWLEDGED/RECONCILIATION");
        assertThat(problems(request)).as("the late timeout opened nothing").isEmpty();
    }

    @Test
    void pauseIsRecorded() {
        RequestCard request = acknowledged();
        carrier.lookupAnswer(request.operationId(), FakeCarrier.accepted(request.operationId()));

        reconciler.check(request.id(), RunType.SCHEDULED, null);
        assertThat(times(request)).isEqualTo(clock.instant() + "/" + clock.instant().plus(Duration.ofMinutes(5)));

        clock.advance(Duration.ofMinutes(5));
        reconciler.check(request.id(), RunType.SCHEDULED, null);
        assertThat(times(request)).isEqualTo(clock.instant() + "/" + clock.instant().plus(Duration.ofMinutes(10)));

        carrier.lookupAnswer(request.operationId(), FakeCarrier.notFound(request.operationId()));
        clock.advance(Duration.ofMinutes(10));
        reconciler.check(request.id(), RunType.SCHEDULED, null);
        assertThat(times(request)).isEqualTo(clock.instant() + "/" + clock.instant().plus(Duration.ofMinutes(5)));
    }

    @Test
    void refusedProblemRollsBackCheck() {
        RequestCard request = acknowledged();
        doThrow(new IllegalStateException("refused")).when(problemDao)
                .open(any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> reconciler.check(request.id(), RunType.SCHEDULED, null))
                .hasMessageContaining("refused");

        assertThat(rows(request)).isEmpty();
        assertThat(times(request)).isEqualTo("-/-");
    }

    private RequestCard acknowledged() {
        RequestCard request = requests.create("1");
        worker.runOnce();
        return request;
    }

    private String delivery(RequestCard request) {
        return jdbc.sql("SELECT delivery_status || '/' || coalesce(acknowledged_via, '-') FROM dispatch_operation"
                        + " WHERE request_id = :id")
                .param("id", request.id()).query(String.class).single();
    }

    private String times(RequestCard request) {
        return jdbc.sql("SELECT reconciled_at, next_reconcile_at FROM dispatch_operation WHERE request_id = :id")
                .param("id", request.id())
                .query((rs, n) -> instant(rs.getObject(1, OffsetDateTime.class)) + "/"
                        + instant(rs.getObject(2, OffsetDateTime.class)))
                .single();
    }

    private static String instant(OffsetDateTime value) {
        return value == null ? "-" : value.toInstant().toString();
    }

    private List<String> rows(RequestCard request) {
        return jdbc.sql("""
                        SELECT outcome || ':' || acknowledged || ':' || delivery_status || ':'
                               || coalesce(carrier_state, '-')
                          FROM reconciliation WHERE request_id = :id ORDER BY checked_at, id""")
                .param("id", request.id()).query(String.class).list();
    }

    private List<String> problems(RequestCard request) {
        return jdbc.sql("""
                        SELECT kind || ':' || status || ':' || coalesce(decision, '-')
                          FROM problem WHERE request_id = :id ORDER BY opened_at, kind""")
                .param("id", request.id()).query(String.class).list();
    }
}
