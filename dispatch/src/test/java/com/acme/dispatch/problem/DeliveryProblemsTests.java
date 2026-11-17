package com.acme.dispatch.problem;

import static com.acme.dispatch.common.SqlTime.toSql;
import static com.acme.dispatch.support.TestClockConfiguration.START;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.acme.dispatch.carrier.SubmitOutcome;
import com.acme.dispatch.carrier.SubmitResult;
import com.acme.dispatch.exchange.ExchangeProperties;
import com.acme.dispatch.exchange.SendWorker;
import com.acme.dispatch.request.RequestCard;
import com.acme.dispatch.support.FakeCarrier;
import com.acme.dispatch.support.IntegrationTest;
import com.acme.dispatch.support.MutableClock;
import com.acme.dispatch.support.OurSide;
import com.acme.dispatch.support.TestRequests;
import com.acme.dispatch.support.TestRows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Problems of delivery are opened and closed together with the send transitions (operator-problems.md §2.3). */
@IntegrationTest
class DeliveryProblemsTests {

    static final SubmitResult TIMEOUT = new SubmitResult(SubmitOutcome.UNKNOWN, null, "No response in time");
    static final SubmitResult TEMPORARY = new SubmitResult(SubmitOutcome.TEMPORARY_ERROR, 503, "HTTP 503");
    static final SubmitResult PERMANENT = new SubmitResult(SubmitOutcome.PERMANENT_ERROR, 400, "HTTP 400");
    static final SubmitResult CONFLICT = new SubmitResult(SubmitOutcome.PAYLOAD_CONFLICT, 409, "HTTP 409");

    @Autowired
    SendWorker worker;
    @Autowired
    FakeCarrier carrier;
    @Autowired
    TestRequests requests;
    @Autowired
    MutableClock clock;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ExchangeProperties properties;
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
    void timeoutOpensDeliveryUnknown() {
        RequestCard request = requests.create("1");
        carrier.answer(TIMEOUT);

        worker.runOnce();

        assertThat(problems(request)).containsExactly("DELIVERY_UNKNOWN:OPEN:-:No response in time");
    }

    @Test
    void confirmationByResendClosesIt() {
        RequestCard request = requests.create("1");
        carrier.answer(TIMEOUT);
        worker.runOnce();

        clock.advance(Duration.ofSeconds(7));
        worker.runOnce();

        assertThat(problems(request)).containsExactly("DELIVERY_UNKNOWN:RESOLVED:SYSTEM:No response in time");
        assertThat(jdbc.sql("SELECT resolved_by IS NULL AND resolved_at = :now FROM problem")
                .param("now", toSql(START.plusSeconds(7)))
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void repeatedTimeoutsKeepOneProblem() {
        RequestCard request = requests.create("1");
        carrier.answer(TIMEOUT, TIMEOUT, TIMEOUT);

        for (int i = 0; i < 3; i++) {
            worker.runOnce();
            clock.advance(Duration.ofMinutes(1));
        }

        assertThat(problems(request)).containsExactly("DELIVERY_UNKNOWN:OPEN:-:No response in time");
    }

    @Test
    void temporaryErrorOpensNothing() {
        RequestCard request = requests.create("1");
        carrier.answer(TEMPORARY);

        worker.runOnce();

        assertThat(problems(request)).isEmpty();
    }

    @Test
    void exhaustedAfterUnknownOpensBoth() {
        RequestCard request = requests.create("1");
        for (int i = 0; i < properties.maxAttempts(); i++) {
            carrier.answer(i == 0 ? TIMEOUT : TEMPORARY);
        }

        for (int i = 0; i < properties.maxAttempts(); i++) {
            worker.runOnce();
            clock.advance(Duration.ofMinutes(10));
        }

        assertThat(problems(request)).containsExactlyInAnyOrder(
                "DELIVERY_UNKNOWN:OPEN:-:No response in time",
                "DELIVERY_FAILED:OPEN:-:Attempts exhausted (8): HTTP 503");
    }

    @Test
    void lastAttemptOfUnknownOutcomeOpensBoth() {
        RequestCard request = requests.create("1");
        lastAttemptDue(request);
        carrier.answer(TIMEOUT);

        worker.runOnce();

        assertThat(problems(request)).containsExactlyInAnyOrder(
                "DELIVERY_UNKNOWN:OPEN:-:No response in time",
                "DELIVERY_FAILED:OPEN:-:Attempts exhausted (8): No response in time");
    }

    @Test
    void permanentErrorOpensDeliveryFailed() {
        RequestCard rejected = requests.create("1");
        carrier.answer(PERMANENT);
        worker.runOnce();
        RequestCard conflicting = requests.create("2");
        carrier.answer(CONFLICT);
        worker.runOnce();

        assertThat(problems(rejected)).containsExactly("DELIVERY_FAILED:OPEN:-:HTTP 400");
        assertThat(problems(conflicting)).containsExactly("DELIVERY_FAILED:OPEN:-:HTTP 409");
    }

    @Test
    void deliveryProblemsHaveNoOurSide() {
        RequestCard unknown = requests.create("1");
        carrier.answer(TIMEOUT);
        worker.runOnce();
        RequestCard failed = requests.create("2");
        carrier.answer(PERMANENT);
        worker.runOnce();

        assertThat(jdbc.sql("SELECT id FROM problem WHERE request_id IN (:ids)")
                .param("ids", List.of(unknown.id(), failed.id()))
                .query(UUID.class).list())
                .hasSize(2)
                .allSatisfy(problem -> assertThat(OurSide.of(jdbc, problem)).isEqualTo(OurSide.NOT_RECORDED));
    }

    @Test
    void interruptedSendOpensDeliveryUnknown() {
        RequestCard request = requests.create("1");
        crashedMidSend(request, 1);

        worker.runOnce();

        assertThat(problems(request))
                .containsExactly("DELIVERY_UNKNOWN:OPEN:-:Send was interrupted (lease expired)");
    }

    @Test
    void interruptedLastAttemptOpensBoth() {
        RequestCard request = requests.create("1");
        crashedMidSend(request, properties.maxAttempts());

        worker.runOnce();

        assertThat(problems(request)).containsExactlyInAnyOrder(
                "DELIVERY_UNKNOWN:OPEN:-:Send was interrupted (lease expired)",
                "DELIVERY_FAILED:OPEN:-:Attempts exhausted (8): Send was interrupted (lease expired)");
    }

    @Test
    void lateWorkerOpensNothing() {
        RequestCard request = requests.create("1");
        AtomicBoolean first = new AtomicBoolean(true);
        // While the call hangs, the lease runs out and another instance recovers and resends successfully.
        carrier.duringSubmit(() -> {
            if (first.getAndSet(false)) {
                clock.set(START.plusSeconds(61));
                worker.runOnce();
                clock.set(START.plusSeconds(68));
                worker.runOnce();
            }
        });
        // The resend (inside the hanging call) is answered first, then the late call times out.
        carrier.answer(FakeCarrier.ACKNOWLEDGED, TIMEOUT);

        worker.runOnce();

        assertThat(problems(request)).as("the late timeout is recorded only in history")
                .containsExactly("DELIVERY_UNKNOWN:RESOLVED:SYSTEM:Send was interrupted (lease expired)");
    }

    @Test
    void refusedProblemRollsBackTheOutcome() {
        RequestCard request = requests.create("1");
        carrier.answer(TIMEOUT);
        doThrow(new DataIntegrityViolationException("simulated: problem refused"))
                .when(problemDao).open(any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> worker.runOnce()).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(deliveryStatus(request)).isEqualTo("SENDING");
        assertThat(problems(request)).isEmpty();
    }

    @Test
    void refusedProblemStopsRecovery() {
        RequestCard crashed = requests.create("1");
        RequestCard waiting = requests.create("2");
        crashedMidSend(crashed, 1);
        doThrow(new DataIntegrityViolationException("simulated: problem refused"))
                .when(problemDao).open(any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> worker.runOnce()).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(deliveryStatus(crashed)).isEqualTo("SENDING");
        assertThat(deliveryStatus(waiting)).as("sending stopped as a whole").isEqualTo("PENDING");
        assertThat(carrier.submitted()).isEmpty();
    }

    private List<String> problems(RequestCard request) {
        return jdbc.sql("""
                        SELECT kind || ':' || status || ':' || coalesce(decision, '-') || ':' || details
                          FROM problem WHERE request_id = :id ORDER BY opened_at, kind""")
                .param("id", request.id())
                .query(String.class)
                .list();
    }

    private String deliveryStatus(RequestCard request) {
        return jdbc.sql("SELECT delivery_status FROM dispatch_operation WHERE request_id = :id")
                .param("id", request.id())
                .query(String.class)
                .single();
    }

    /** The next attempt is the last one the policy allows. */
    private void lastAttemptDue(RequestCard request) {
        jdbc.sql("""
                        UPDATE dispatch_operation SET delivery_status = 'RETRY_WAIT', attempts = :attempts
                         WHERE request_id = :id""")
                .param("attempts", properties.maxAttempts() - 1)
                .param("id", request.id())
                .update();
    }

    /** A worker claimed the operation as attempt {@code attempt} a minute ago and died: the lease is over. */
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
}
