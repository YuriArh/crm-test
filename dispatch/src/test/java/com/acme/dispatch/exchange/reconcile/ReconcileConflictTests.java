package com.acme.dispatch.exchange.reconcile;

import static com.acme.dispatch.support.DemoData.NORTH_OPERATOR;
import static com.acme.dispatch.support.TestClockConfiguration.START;
import static com.acme.dispatch.support.TestFeed.succeeded;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.acme.dispatch.exchange.SendWorker;
import com.acme.dispatch.problem.ProblemDao;
import com.acme.dispatch.problem.ProblemDecision;
import com.acme.dispatch.request.RequestCard;
import com.acme.dispatch.support.FakeCarrier;
import com.acme.dispatch.support.IntegrationTest;
import com.acme.dispatch.support.MutableClock;
import com.acme.dispatch.support.OurSide;
import com.acme.dispatch.support.TestRequests;
import com.acme.dispatch.support.TestResults;
import com.acme.dispatch.support.TestRows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * CONFLICT is shown to an operator and changes nothing; the problem follows the carrier's latest answer, closes when
 * the results meet, and is not opened again for an answer the operator already kept ours against
 * ({@code docs/specs/reconciliation.md} §2.5).
 */
@IntegrationTest
class ReconcileConflictTests {

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
    ProblemDao problemDao;

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
    void differentResultsOpenConflict() {
        RequestCard request = withOurResult();
        carrierHas(request, "700.00");

        Reconciler.Checked checked = check(request);

        assertThat(checked.outcome()).isEqualTo(ReconciliationOutcome.CONFLICT);
        assertThat(problems(request)).containsExactly("RECONCILIATION_CONFLICT:OPEN:-");
        assertThat(linkedRow(request)).isEqualTo(checked.reconciliationId());
        assertThat(result(request)).as("nothing changed").isEqualTo("SUCCEEDED/612.50/1");
    }

    @Test
    void conflictRecordsOurResult() {
        RequestCard request = withOurResult();
        carrierHas(request, "700.00");

        check(request);

        assertThat(OurSide.of(jdbc, openProblem(request))).isEqualTo("SUCCEEDED/TRK-00000001/612.50/-");
    }

    @Test
    void relinkUpdatesOurSideWithRow() {
        RequestCard request = withOurResult();
        carrierHas(request, "700.00");
        check(request);
        jdbc.sql("UPDATE delivery_request SET price = 650.00, version = version + 1 WHERE id = :id")
                .param("id", request.id()).update();
        clock.advance(Duration.ofMinutes(5));

        Reconciler.Checked second = check(request);

        assertThat(linkedRow(request)).isEqualTo(second.reconciliationId());
        assertThat(OurSide.of(jdbc, openProblem(request))).as("both sides of the same check")
                .isEqualTo("SUCCEEDED/TRK-00000001/650.00/-");
    }

    @Test
    void snapshotStaysAfterKeepOurs() {
        RequestCard request = withOurResult();
        carrierHas(request, "700.00");
        check(request);
        UUID problem = keepOurs(request);
        jdbc.sql("UPDATE delivery_request SET price = 650.00, version = version + 1 WHERE id = :id")
                .param("id", request.id()).update();
        clock.advance(Duration.ofMinutes(5));

        check(request);

        assertThat(OurSide.of(jdbc, problem)).as("a closed problem is history")
                .isEqualTo("SUCCEEDED/TRK-00000001/612.50/-");
        assertThat(jdbc.sql("SELECT reconciliation_id FROM problem WHERE id = :id").param("id", problem)
                .query(UUID.class).single()).isNotEqualTo(linkedRow(request));
    }

    @Test
    void conflictRowKeepsBothSides() {
        RequestCard request = withOurResult();
        carrierHas(request, "700.00");

        check(request);

        assertThat(sides(request)).isEqualTo("ours SUCCEEDED/TRK-00000001/612.50/- carrier SUCCEEDED/TRK-00000001/700.00/-");
    }

    @Test
    void carrierWithoutResultRowKeepsOurSide() {
        RequestCard request = withOurResult();
        carrier.lookupAnswer(request.operationId(), FakeCarrier.accepted(request.operationId()));

        check(request);

        assertThat(sides(request)).isEqualTo("ours SUCCEEDED/TRK-00000001/612.50/- carrier -/-/-/-");
    }

    @Test
    void matchRowKeepsEqualSides() {
        RequestCard request = withOurResult();
        carrierHas(request, "612.50");

        assertThat(check(request).outcome()).isEqualTo(ReconciliationOutcome.MATCH);

        assertThat(sides(request)).isEqualTo("ours SUCCEEDED/TRK-00000001/612.50/- carrier SUCCEEDED/TRK-00000001/612.50/-");
    }

    @Test
    void repeatedConflictRelinksOpenProblem() {
        RequestCard request = withOurResult();
        carrierHas(request, "700.00");
        check(request);
        clock.advance(Duration.ofMinutes(5));
        carrierHas(request, "800.00");

        Reconciler.Checked second = check(request);

        assertThat(problems(request)).containsExactly("RECONCILIATION_CONFLICT:OPEN:-");
        assertThat(linkedRow(request)).isEqualTo(second.reconciliationId());
        assertThat(jdbc.sql("SELECT details FROM problem WHERE request_id = :id").param("id", request.id())
                .query(String.class).single()).contains("800");
    }

    @Test
    void carrierWithoutResultIsConflict() {
        RequestCard request = withOurResult();
        carrier.lookupAnswer(request.operationId(), FakeCarrier.accepted(request.operationId()));

        check(request);

        assertThat(jdbc.sql("""
                        SELECT outcome || ':' || carrier_state || ':' || coalesce(carrier_result_status, '-')
                          FROM reconciliation WHERE request_id = :id""")
                .param("id", request.id()).query(String.class).single()).isEqualTo("CONFLICT:ACCEPTED:-");
        assertThat(problems(request)).containsExactly("RECONCILIATION_CONFLICT:OPEN:-");
    }

    @Test
    void matchClosesConflict() {
        RequestCard request = withOurResult();
        carrier.lookupAnswer(request.operationId(), FakeCarrier.accepted(request.operationId()));
        check(request);
        clock.advance(Duration.ofMinutes(5));
        carrierHas(request, "612.50");

        assertThat(check(request).outcome()).isEqualTo(ReconciliationOutcome.MATCH);

        assertThat(problems(request)).containsExactly("RECONCILIATION_CONFLICT:RESOLVED:SYSTEM");
    }

    @Test
    void sameAnswerAfterKeepOursOpensNothing() {
        RequestCard request = withOurResult();
        carrierHas(request, "700.00");
        check(request);
        UUID problem = keepOurs(request);
        clock.advance(Duration.ofMinutes(5));

        check(request);

        assertThat(problems(request)).containsExactly("RECONCILIATION_CONFLICT:RESOLVED:KEEP_OURS");
        assertThat(lastDetails(request)).contains("decided earlier: problem " + problem);
    }

    @Test
    void otherAnswerAfterKeepOursOpensNew() {
        RequestCard request = withOurResult();
        carrier.lookupAnswer(request.operationId(), FakeCarrier.accepted(request.operationId()));
        check(request);
        keepOurs(request);
        clock.advance(Duration.ofMinutes(5));
        carrierHas(request, "700.00");

        check(request);

        assertThat(problems(request)).containsExactly("RECONCILIATION_CONFLICT:RESOLVED:KEEP_OURS",
                "RECONCILIATION_CONFLICT:OPEN:-");
    }

    @Test
    void ourResultChangedAfterKeepOursOpensNew() {
        RequestCard request = withOurResult();
        carrierHas(request, "700.00");
        check(request);
        keepOurs(request);
        jdbc.sql("UPDATE delivery_request SET price = 650.00, version = version + 1 WHERE id = :id")
                .param("id", request.id()).update();
        clock.advance(Duration.ofMinutes(5));

        check(request);

        assertThat(problems(request)).containsExactly("RECONCILIATION_CONFLICT:RESOLVED:KEEP_OURS",
                "RECONCILIATION_CONFLICT:OPEN:-");
    }

    private RequestCard withOurResult() {
        RequestCard request = requests.create("1");
        worker.runOnce();
        results.deliver(succeeded(1, request.operationId(), "TRK-00000001", "612.50"));
        return request;
    }

    private void carrierHas(RequestCard request, String price) {
        carrier.lookupAnswer(request.operationId(), FakeCarrier.executed(request.operationId(),
                succeeded(1, request.operationId(), "TRK-00000001", price)));
    }

    private Reconciler.Checked check(RequestCard request) {
        return reconciler.check(request.id(), RunType.SCHEDULED, null);
    }

    private UUID keepOurs(RequestCard request) {
        UUID problem = jdbc.sql("SELECT id FROM problem WHERE request_id = :id AND status = 'OPEN'")
                .param("id", request.id()).query(UUID.class).single();
        problemDao.closeById(problem, ProblemDecision.KEEP_OURS, NORTH_OPERATOR, "checked with the carrier",
                clock.instant());
        return problem;
    }

    private UUID openProblem(RequestCard request) {
        return jdbc.sql("SELECT id FROM problem WHERE request_id = :id AND status = 'OPEN'")
                .param("id", request.id()).query(UUID.class).single();
    }

    private UUID linkedRow(RequestCard request) {
        return jdbc.sql("SELECT reconciliation_id FROM problem WHERE request_id = :id AND status = 'OPEN'")
                .param("id", request.id()).query(UUID.class).single();
    }

    private String lastDetails(RequestCard request) {
        return jdbc.sql("""
                        SELECT details FROM reconciliation WHERE request_id = :id
                         ORDER BY checked_at DESC, id DESC LIMIT 1""")
                .param("id", request.id()).query(String.class).single();
    }

    /** Both sides of the request's newest check, as {@code ours S/T/P/R carrier S/T/P/R}. */
    private String sides(RequestCard request) {
        return jdbc.sql("""
                        SELECT 'ours ' || coalesce(local_status, '-') || '/' || coalesce(local_tracking_number, '-')
                               || '/' || coalesce(local_price::text, '-') || '/' || coalesce(local_reason, '-')
                               || ' carrier ' || coalesce(carrier_result_status, '-')
                               || '/' || coalesce(carrier_tracking_number, '-')
                               || '/' || coalesce(carrier_price::text, '-') || '/' || coalesce(carrier_reason, '-')
                          FROM reconciliation WHERE request_id = :id
                         ORDER BY checked_at DESC, id DESC LIMIT 1""")
                .param("id", request.id()).query(String.class).single();
    }

    private String result(RequestCard request) {
        return jdbc.sql("SELECT business_status || '/' || price || '/' || version FROM delivery_request WHERE id = :id")
                .param("id", request.id()).query(String.class).single();
    }

    private List<String> problems(RequestCard request) {
        return jdbc.sql("""
                        SELECT kind || ':' || status || ':' || coalesce(decision, '-')
                          FROM problem WHERE request_id = :id ORDER BY opened_at, status DESC""")
                .param("id", request.id()).query(String.class).list();
    }
}
