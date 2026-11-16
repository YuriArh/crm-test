package com.acme.dispatch.problem;

import static com.acme.dispatch.common.SqlTime.toSql;
import static com.acme.dispatch.support.DemoData.NORTH_OPERATOR;
import static com.acme.dispatch.support.DemoData.NORTH_OPERATOR_LOGIN;
import static com.acme.dispatch.support.TestClockConfiguration.START;
import static com.acme.dispatch.support.TestSecurity.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.acme.dispatch.auth.AppUserRepository;
import com.acme.dispatch.carrier.SubmitOutcome;
import com.acme.dispatch.carrier.SubmitResult;
import com.acme.dispatch.exchange.ExchangeProperties;
import com.acme.dispatch.exchange.SendWorker;
import com.acme.dispatch.request.RequestCard;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** «Повторить отправку»: a new budget of retries with the same operationId (operator-problems.md §2.4). */
@IntegrationTest
class RetryActionTests {

    static final SubmitResult TIMEOUT = new SubmitResult(SubmitOutcome.UNKNOWN, null, "No response in time");
    static final SubmitResult TEMPORARY = new SubmitResult(SubmitOutcome.TEMPORARY_ERROR, 503, "HTTP 503");
    static final SubmitResult PERMANENT = new SubmitResult(SubmitOutcome.PERMANENT_ERROR, 400, "HTTP 400");

    @Autowired
    MockMvc mvc;
    @Autowired
    AppUserRepository users;
    @Autowired
    TestRequests requests;
    @Autowired
    SendWorker worker;
    @Autowired
    FakeCarrier carrier;
    @Autowired
    ExchangeProperties properties;
    @Autowired
    MutableClock clock;
    @Autowired
    JdbcClient jdbc;

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
    void retryOfFailedStartsANewBudget() throws Exception {
        RequestCard request = failed();
        clock.set(START.plusSeconds(30));

        retry(request, "k-1")
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.id").value(request.id().toString()))
                .andExpect(jsonPath("$.deliveryStatus").value("PENDING"))
                .andExpect(jsonPath("$.operationId").value(request.operationId().toString()))
                .andExpect(jsonPath("$.actions", hasSize(0)))
                .andExpect(jsonPath("$.problems[0].kind").value("DELIVERY_FAILED"))
                .andExpect(jsonPath("$.problems[0].status").value("RESOLVED"))
                .andExpect(jsonPath("$.problems[0].decision").value("RETRIED"))
                .andExpect(jsonPath("$.problems[0].resolvedBy.id").value(NORTH_OPERATOR.toString()))
                .andExpect(jsonPath("$.problems[0].resolvedAt").value("2026-11-12T09:00:30Z"));

        assertThat(operation(request)).isEqualTo("PENDING|1|1|2026-11-12 09:00:30+00|-");

        assertThat(worker.runOnce()).isEqualTo(1);
        assertThat(operation(request)).startsWith("ACKNOWLEDGED|2|1|");
        assertThat(carrier.submitted()).extracting(sent -> sent.operationId())
                .containsExactly(request.operationId(), request.operationId());
    }

    @Test
    void retryOfUnknownSendsNowAndKeepsTheAmbiguity() throws Exception {
        RequestCard request = requests.create("1");
        carrier.answer(TIMEOUT);
        worker.runOnce();
        clock.set(START.plusSeconds(1));

        retry(request, "k-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$.problems[0].kind").value("DELIVERY_UNKNOWN"))
                .andExpect(jsonPath("$.problems[0].status").value("OPEN"));
        assertThat(operation(request)).isEqualTo("UNKNOWN|1|1|2026-11-12 09:00:01+00|-");

        assertThat(worker.runOnce()).as("sent at once, not after the pause").isEqualTo(1);
        assertThat(problems(request)).containsExactly("DELIVERY_UNKNOWN:RESOLVED:SYSTEM");
    }

    @Test
    void retryIsRefusedInOtherStates() throws Exception {
        RequestCard pending = requests.create("1");
        expectRefused(pending, "PENDING");

        RequestCard acknowledged = requests.create("2");
        worker.runOnce();
        expectRefused(acknowledged, "ACKNOWLEDGED");

        RequestCard waiting = requests.create("3");
        carrier.answer(TEMPORARY);
        worker.runOnce();
        expectRefused(waiting, "RETRY_WAIT");

        RequestCard sending = requests.create("4");
        jdbc.sql("""
                        UPDATE dispatch_operation SET delivery_status = 'SENDING', attempts = 1, locked_until = :lease
                         WHERE request_id = :id""")
                .param("lease", toSql(START.plusSeconds(60)))
                .param("id", sending.id())
                .update();
        expectRefused(sending, "SENDING");
    }

    @Test
    void newBudgetGetsFullAttemptsAndShortPause() throws Exception {
        int max = properties.maxAttempts();
        RequestCard request = requests.create("1");
        for (int i = 0; i < max; i++) {
            carrier.answer(TEMPORARY);
            worker.runOnce();
            clock.advance(Duration.ofMinutes(10));
        }
        assertThat(operation(request)).startsWith("FAILED|" + max + "|0|");

        retry(request, "k-1").andExpect(status().isOk());
        carrier.answer(TEMPORARY);
        worker.runOnce();

        assertThat(operation(request)).startsWith("RETRY_WAIT|" + (max + 1) + "|" + max + "|");
        Duration pause = Duration.between(clock.instant(), jdbc.sql(
                        "SELECT next_attempt_at FROM dispatch_operation WHERE request_id = :id")
                .param("id", request.id()).query(java.time.OffsetDateTime.class).single().toInstant());
        assertThat(pause).isBetween(Duration.ofSeconds(4), Duration.ofSeconds(6));
    }

    @Test
    void lateWorkerAfterRetryIsFenced() throws Exception {
        int max = properties.maxAttempts();
        RequestCard request = requests.create("1");
        jdbc.sql("UPDATE dispatch_operation SET delivery_status = 'RETRY_WAIT', attempts = :attempts WHERE request_id = :id")
                .param("attempts", max - 1)
                .param("id", request.id())
                .update();
        AtomicBoolean first = new AtomicBoolean(true);
        // The last attempt hangs past its lease: recovery fails the operation, an operator retries it, and the
        // new attempt is acknowledged — all before the hanging call returns.
        carrier.duringSubmit(() -> {
            if (first.getAndSet(false)) {
                clock.set(START.plusSeconds(61));
                worker.runOnce();
                try {
                    retry(request, "k-1").andExpect(status().isOk());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                worker.runOnce();
            }
        });
        carrier.answer(FakeCarrier.ACKNOWLEDGED, TIMEOUT);

        worker.runOnce();

        assertThat(operation(request)).startsWith("ACKNOWLEDGED|" + (max + 1) + "|" + max + "|");
        assertThat(jdbc.sql("""
                        SELECT attempt_no || ':' || outcome FROM exchange_attempt
                         WHERE operation_id = :id ORDER BY attempt_no""")
                .param("id", request.operationId())
                .query(String.class).list())
                .containsExactly(max + ":UNKNOWN", (max + 1) + ":ACKNOWLEDGED");
    }

    @Test
    void sameKeyReplaysTheResponse() throws Exception {
        RequestCard request = failed();
        String first = retry(request, "k-1").andReturn().getResponse().getContentAsString();
        clock.advance(Duration.ofSeconds(10));
        jdbc.sql("UPDATE dispatch_operation SET attempts = 3 WHERE request_id = :id")
                .param("id", request.id())
                .update();

        String second = retry(request, "k-1")
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        assertThat(operation(request)).as("the budget is not started again").startsWith("PENDING|3|1|");
    }

    @Test
    void sameKeyForAnotherRequestIsRejected() throws Exception {
        RequestCard first = failed();
        RequestCard second = failed();
        retry(first, "k-1").andExpect(status().isOk());

        retry(second, "k-1")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(operation(second)).startsWith("FAILED|");
    }

    @Test
    void keyIsRequired() throws Exception {
        RequestCard request = failed();

        mvc.perform(post("/api/requests/{id}/retry", request.id()).with(csrf())
                        .with(asUser(users, NORTH_OPERATOR_LOGIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        assertThat(operation(request)).startsWith("FAILED|");
    }

    @Test
    void malformedIdIsNotFound() throws Exception {
        mvc.perform(post("/api/requests/{id}/retry", "not-an-id").header("Idempotency-Key", "k-1").with(csrf())
                        .with(asUser(users, NORTH_OPERATOR_LOGIN)))
                .andExpect(status().isNotFound());
    }

    private RequestCard failed() {
        RequestCard request = requests.create("1");
        carrier.answer(PERMANENT);
        worker.runOnce();
        return request;
    }

    private void expectRefused(RequestCard request, String deliveryStatus) throws Exception {
        String before = operation(request);
        assertThat(before).startsWith(deliveryStatus + "|");

        retry(request, UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));

        assertThat(operation(request)).isEqualTo(before);
    }

    private ResultActions retry(RequestCard request, String key) throws Exception {
        return mvc.perform(post("/api/requests/{id}/retry", request.id()).header("Idempotency-Key", key)
                .with(csrf()).with(asUser(users, NORTH_OPERATOR_LOGIN)));
    }

    /** {@code status|attempts|retry_from|next_attempt_at|last_error or -}. */
    private String operation(RequestCard request) {
        return jdbc.sql("""
                        SELECT delivery_status || '|' || attempts || '|' || retry_from || '|' || next_attempt_at
                               || '|' || coalesce(last_error, '-')
                          FROM dispatch_operation WHERE request_id = :id""")
                .param("id", request.id())
                .query(String.class)
                .single();
    }

    private List<String> problems(RequestCard request) {
        return jdbc.sql("""
                        SELECT kind || ':' || status || ':' || coalesce(decision, '-')
                          FROM problem WHERE request_id = :id ORDER BY opened_at, kind""")
                .param("id", request.id())
                .query(String.class)
                .list();
    }
}
