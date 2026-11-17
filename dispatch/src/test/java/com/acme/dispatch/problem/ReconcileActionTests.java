package com.acme.dispatch.problem;

import static com.acme.dispatch.common.SqlTime.toSql;
import static com.acme.dispatch.support.DemoData.NORTH_OPERATOR;
import static com.acme.dispatch.support.DemoData.NORTH_OPERATOR_LOGIN;
import static com.acme.dispatch.support.TestClockConfiguration.START;
import static com.acme.dispatch.support.TestFeed.succeeded;
import static com.acme.dispatch.support.TestSecurity.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;

import com.acme.dispatch.auth.AppUserRepository;
import com.acme.dispatch.exchange.SendWorker;
import com.acme.dispatch.request.RequestCard;
import com.acme.dispatch.support.FakeCarrier;
import com.acme.dispatch.support.IntegrationTest;
import com.acme.dispatch.support.MutableClock;
import com.acme.dispatch.support.TestRequests;
import com.acme.dispatch.support.TestRows;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** «Сверить сейчас»: one check of the request, recorded and returned (reconciliation.md §2.7). */
@IntegrationTest
class ReconcileActionTests {

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
    void operatorReconcilesAndSeesOutcome() throws Exception {
        RequestCard request = acknowledged();
        carrier.lookupAnswer(request.operationId(), FakeCarrier.accepted(request.operationId()));

        reconcile(request.id().toString(), "k-1")
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.trigger").value("MANUAL"))
                .andExpect(jsonPath("$.performedBy.id").value(NORTH_OPERATOR.toString()))
                .andExpect(jsonPath("$.outcome").value("MATCH"))
                .andExpect(jsonPath("$.carrierState").value("ACCEPTED"))
                .andExpect(jsonPath("$.checkedAt").value("2026-11-12T09:00:00Z"));

        assertThat(jdbc.sql("SELECT run_type || ':' || performed_by FROM reconciliation WHERE request_id = :id")
                .param("id", request.id()).query(String.class).single()).isEqualTo("MANUAL:" + NORTH_OPERATOR);
    }

    @Test
    void aheadResultVisibleRightAfterResponse() throws Exception {
        RequestCard request = acknowledged();
        carrier.lookupAnswer(request.operationId(), FakeCarrier.executed(request.operationId(),
                succeeded(1, request.operationId(), "TRK-00000001", "612.50")));

        reconcile(request.id().toString(), "k-1")
                .andExpect(jsonPath("$.outcome").value("EXTERNAL_AHEAD"))
                .andExpect(jsonPath("$.carrierResult.trackingNumber").value("TRK-00000001"))
                .andExpect(jsonPath("$.localResult").value(nullValue()));

        mvc.perform(get("/api/requests/{id}", request.id()).with(asUser(users, NORTH_OPERATOR_LOGIN)))
                .andExpect(jsonPath("$.businessStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.result.trackingNumber").value("TRK-00000001"))
                .andExpect(jsonPath("$.reconciliations[0].outcome").value("EXTERNAL_AHEAD"));
    }

    @Test
    void unavailableIsAnOutcome() throws Exception {
        RequestCard request = acknowledged();
        carrier.lookupUnavailable(true);

        reconcile(request.id().toString(), "k-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ERROR"))
                .andExpect(jsonPath("$.carrierState").doesNotExist());

        assertThat(jdbc.sql("SELECT outcome FROM reconciliation WHERE request_id = :id")
                .param("id", request.id()).query(String.class).single()).isEqualTo("ERROR");
    }

    @Test
    void sameKeyReplaysWithoutAskingCarrier() throws Exception {
        RequestCard request = acknowledged();
        carrier.lookupAnswer(request.operationId(), FakeCarrier.accepted(request.operationId()));
        String first = reconcile(request.id().toString(), "k-1").andReturn().getResponse().getContentAsString();
        clock.advance(Duration.ofMinutes(1));

        reconcile(request.id().toString(), "k-1")
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(JsonPath.<String>read(first, "$.id")));

        assertThat(carrier.lookedUp()).hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM reconciliation").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void sameKeyReplaysAnError() throws Exception {
        RequestCard request = acknowledged();
        carrier.lookupUnavailable(true);
        reconcile(request.id().toString(), "k-1");
        carrier.lookupUnavailable(false);

        reconcile(request.id().toString(), "k-1")
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.outcome").value("ERROR"));
        reconcile(request.id().toString(), "k-2")
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.outcome").value("EXTERNAL_MISSING"));
    }

    @Test
    void sameKeyOtherRequestIsRejected() throws Exception {
        RequestCard first = acknowledged();
        RequestCard second = acknowledged();
        reconcile(first.id().toString(), "k-1");

        reconcile(second.id().toString(), "k-1")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void missingKeyIs400() throws Exception {
        RequestCard request = acknowledged();

        mvc.perform(post("/api/requests/{id}/reconcile", request.id()).with(csrf())
                        .with(asUser(users, NORTH_OPERATOR_LOGIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        assertThat(carrier.lookedUp()).isEmpty();
    }

    @Test
    void unknownOrMalformedIdIs404() throws Exception {
        reconcile(UUID.randomUUID().toString(), "k-1").andExpect(status().isNotFound());
        reconcile("not-a-uuid", "k-2").andExpect(status().isNotFound());
        assertThat(carrier.lookedUp()).isEmpty();
    }

    @Test
    void manualIgnoresDueTime() throws Exception {
        RequestCard request = acknowledged();
        jdbc.sql("UPDATE dispatch_operation SET reconciled_at = :now, next_reconcile_at = :later WHERE request_id = :id")
                .param("now", toSql(START)).param("later", toSql(START.plus(Duration.ofHours(1))))
                .param("id", request.id()).update();

        reconcile(request.id().toString(), "k-1").andExpect(status().isOk());

        assertThat(carrier.lookedUp()).containsExactly(request.operationId());
    }

    private RequestCard acknowledged() {
        RequestCard request = requests.create("1");
        worker.runOnce();
        return request;
    }

    private ResultActions reconcile(String id, String key) throws Exception {
        return mvc.perform(post("/api/requests/{id}/reconcile", id).header("Idempotency-Key", key)
                .with(csrf()).with(asUser(users, NORTH_OPERATOR_LOGIN)));
    }
}
