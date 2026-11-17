package com.acme.dispatch.exchange.reconcile;

import static com.acme.dispatch.common.SqlTime.toSql;
import static com.acme.dispatch.support.TestClockConfiguration.START;
import static com.acme.dispatch.support.TestFeed.succeeded;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.acme.dispatch.carrier.SubmitOutcome;
import com.acme.dispatch.carrier.SubmitResult;
import com.acme.dispatch.exchange.SendWorker;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The background pass: who is checked, how many, in what order, and when it stops ({@code reconciliation.md} §2.1). */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class ReconcilerTests {

    static final SubmitResult TIMEOUT = new SubmitResult(SubmitOutcome.UNKNOWN, null, "No response in time");
    static final SubmitResult TEMPORARY = new SubmitResult(SubmitOutcome.TEMPORARY_ERROR, 503, "HTTP 503");
    static final SubmitResult PERMANENT = new SubmitResult(SubmitOutcome.PERMANENT_ERROR, 400, "HTTP 400");

    @Autowired
    Reconciler reconciler;
    @Autowired
    ReconciliationDao dao;
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

    long feedSeq;

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
    void picksDueOperationsOfTheBackgroundSet() {
        RequestCard unknown = sent(TIMEOUT);
        RequestCard failed = sent(PERMANENT);
        RequestCard waitingForResult = sent(null);
        RequestCard lostByCarrier = withResult();
        reconciler.check(lostByCarrier.id(), RunType.MANUAL, com.acme.dispatch.support.DemoData.NORTH_OPERATOR);
        RequestCard disputed = withResult();
        carrier.lookupAnswer(disputed.operationId(), FakeCarrier.accepted(disputed.operationId()));
        reconciler.check(disputed.id(), RunType.MANUAL, com.acme.dispatch.support.DemoData.NORTH_OPERATOR);
        carrier.reset();
        clock.advance(Duration.ofMinutes(10));

        reconciler.runOnce(RunType.SCHEDULED);

        assertThat(carrier.lookedUp()).containsExactlyInAnyOrder(unknown.operationId(), failed.operationId(),
                waitingForResult.operationId(), lostByCarrier.operationId(), disputed.operationId());
    }

    /** Each state is made last-but-not-touched: a later send pass would move the requests made before it. */
    @Test
    void skipsOthers() {
        withResult();                                           // final, nothing open
        RequestCard checkedRecently = sent(PERMANENT);          // FAILED, but its next check is not due
        schedule(checkedRecently, clock.instant(), clock.instant().plus(Duration.ofMinutes(5)));
        clock.advance(Duration.ofMinutes(1));
        sent(null);                                             // acknowledged a moment ago
        sent(TIMEOUT);                                          // UNKNOWN a moment ago: its resend clears it up
        sent(TEMPORARY);                                        // RETRY_WAIT
        requests.create("1");                                   // PENDING: not sent yet

        reconciler.runOnce(RunType.SCHEDULED);

        assertThat(carrier.lookedUp()).isEmpty();
    }

    @Test
    void perPassLimitAndOrder() {
        RequestCard neverChecked = sent(TIMEOUT);
        RequestCard dueEarlier = sent(TIMEOUT);
        RequestCard dueLater = sent(TIMEOUT);
        clock.advance(Duration.ofMinutes(3));
        Instant now = clock.instant();
        schedule(dueEarlier, now.minus(Duration.ofMinutes(20)), now.minus(Duration.ofMinutes(2)));
        schedule(dueLater, now.minus(Duration.ofMinutes(20)), now.minus(Duration.ofMinutes(1)));

        List<UUID> due = dao.findDue(now, now.minus(Duration.ofMinutes(2)), 2);

        assertThat(due).containsExactly(neverChecked.id(), dueEarlier.id());
    }

    @Test
    void unavailableStopsPass() {
        sent(TIMEOUT);
        sent(TIMEOUT);
        clock.advance(Duration.ofMinutes(3));
        carrier.lookupUnavailable(true);

        reconciler.runOnce(RunType.SCHEDULED);

        assertThat(carrier.lookedUp()).hasSize(1);
        assertThat(jdbc.sql("SELECT outcome FROM reconciliation").query(String.class).list()).containsExactly("ERROR");
    }

    @Test
    void passIsLogged(CapturedOutput output) {
        reconciler.runOnce(RunType.SCHEDULED);
        assertThat(output.getOut()).doesNotContain("Reconciliation SCHEDULED");

        sent(TIMEOUT);
        clock.advance(Duration.ofMinutes(3));
        reconciler.runOnce(RunType.SCHEDULED);

        assertThat(output.getOut()).contains("Reconciliation SCHEDULED: checked 1 — MATCH 0, EXTERNAL_AHEAD 0, "
                + "EXTERNAL_MISSING 1, CONFLICT 0, ERROR 0");
    }

    /** A request sent once with this answer ({@code null}: acknowledged). */
    private RequestCard sent(SubmitResult answer) {
        RequestCard request = requests.create("1");
        if (answer != null) {
            carrier.answer(answer);
        }
        worker.runOnce();
        return request;
    }

    private RequestCard withResult() {
        RequestCard request = sent(null);
        results.deliver(succeeded(++feedSeq, request.operationId(), "TRK-00000001", "612.50"));
        return request;
    }

    private void schedule(RequestCard request, Instant reconciledAt, Instant next) {
        jdbc.sql("UPDATE dispatch_operation SET reconciled_at = :at, next_reconcile_at = :next WHERE request_id = :id")
                .param("at", toSql(reconciledAt)).param("next", toSql(next)).param("id", request.id()).update();
    }
}
