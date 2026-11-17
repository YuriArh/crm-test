package com.acme.dispatch.problem;

import static com.acme.dispatch.support.DemoData.NORTH_OPERATOR;
import static com.acme.dispatch.support.DemoData.NORTH_OPERATOR_LOGIN;
import static com.acme.dispatch.support.TestClockConfiguration.START;
import static com.acme.dispatch.support.TestFeed.atFeedSeq;
import static com.acme.dispatch.support.TestFeed.succeeded;
import static com.acme.dispatch.support.TestSecurity.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.acme.dispatch.auth.AppUserRepository;
import com.acme.dispatch.exchange.SendWorker;
import com.acme.dispatch.exchange.inbox.InboxSource;
import com.acme.dispatch.exchange.reconcile.Reconciler;
import com.acme.dispatch.exchange.reconcile.RunType;
import com.acme.dispatch.request.RequestCard;
import com.acme.dispatch.support.FakeCarrier;
import com.acme.dispatch.support.IntegrationTest;
import com.acme.dispatch.support.MutableClock;
import com.acme.dispatch.support.TestRequests;
import com.acme.dispatch.support.TestResults;
import com.acme.dispatch.support.TestRows;

import tools.jackson.databind.JsonNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Decisions on the problems reconciliation opens (reconciliation.md §2.7). */
@IntegrationTest
class ResolveReconciliationProblemTests {

    @Autowired
    MockMvc mvc;
    @Autowired
    AppUserRepository users;
    @Autowired
    TestRequests requests;
    @Autowired
    TestResults results;
    @Autowired
    SendWorker worker;
    @Autowired
    Reconciler reconciler;
    @Autowired
    FakeCarrier carrier;
    @Autowired
    MutableClock clock;
    @Autowired
    JdbcClient jdbc;

    long feedSeq = 100;

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
    void applyCarrierReplacesResult() throws Exception {
        RequestCard request = withOurResult();
        JsonNode carriers = carrierHas(request, "TRK-00000002", "700.00");
        UUID problem = check(request);

        resolve(problem, "APPLY_CARRIER", "Перевозчик подтвердил новую цену", "k-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.decision").value("APPLY_CARRIER"))
                .andExpect(jsonPath("$.resolvedBy.id").value(NORTH_OPERATOR.toString()));

        assertThat(result(request)).isEqualTo("SUCCEEDED|TRK-00000002|700.00|2");
        String messageId = carriers.get("messageId").asString();
        assertThat(jdbc.sql("""
                        SELECT i.processing_status || '|' || i.reason || '|' || (i.id = o.applied_inbox_id)
                          FROM inbox i JOIN dispatch_operation o ON o.request_id = :id
                         WHERE i.source = 'RECONCILIATION' AND i.message_id = :messageId""")
                .param("id", request.id()).param("messageId", messageId).query(String.class).single())
                .isEqualTo("APPLIED|Applied by operator decision " + problem + "|true");
    }

    @Test
    void feedRepeatAfterApplyCarrierIsStale() throws Exception {
        RequestCard request = withOurResult();
        JsonNode carriers = carrierHas(request, "TRK-00000002", "700.00");
        UUID problem = check(request);
        resolve(problem, "APPLY_CARRIER", "ok", "k-1").andExpect(status().isOk());

        assertThat(results.deliver(atFeedSeq(carriers, ++feedSeq))).as("the carrier's result from the feed")
                .isEqualTo("STALE");
        assertThat(result(request)).isEqualTo("SUCCEEDED|TRK-00000002|700.00|2");
        assertThat(openProblems(request)).isEmpty();
    }

    @Test
    void applyCarrierUsesExistingRecord() throws Exception {
        RequestCard request = withOurResult();
        JsonNode carriers = carrierHas(request, "TRK-00000002", "700.00");
        UUID problem = check(request);
        results.receive(withoutFeedSeq(carriers), InboxSource.RECONCILIATION);

        resolve(problem, "APPLY_CARRIER", "ok", "k-1").andExpect(status().isOk());

        assertThat(jdbc.sql("SELECT processing_status FROM inbox WHERE source = 'RECONCILIATION'")
                .query(String.class).list()).containsExactly("APPLIED");
        assertThat(result(request)).isEqualTo("SUCCEEDED|TRK-00000002|700.00|2");
    }

    @Test
    void applyCarrierWithoutCarrierResultIs409() throws Exception {
        RequestCard request = withOurResult();
        carrier.lookupAnswer(request.operationId(), FakeCarrier.accepted(request.operationId()));
        UUID problem = check(request);

        resolve(problem, "APPLY_CARRIER", "ok", "k-1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        assertThat(result(request)).isEqualTo("SUCCEEDED|TRK-00000001|612.50|1");
    }

    @Test
    void keepOursClosesConflict() throws Exception {
        RequestCard request = withOurResult();
        carrierHas(request, "TRK-00000002", "700.00");
        UUID problem = check(request);

        resolve(problem, "KEEP_OURS", "Наш итог верный", "k-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("KEEP_OURS"))
                .andExpect(jsonPath("$.comment").value("Наш итог верный"));

        assertThat(result(request)).isEqualTo("SUCCEEDED|TRK-00000001|612.50|1");
    }

    @Test
    void keepOursOnExternalMissingWithResult() throws Exception {
        RequestCard request = withOurResult();
        UUID problem = check(request);

        resolve(problem, "KEEP_OURS", "Перевозчик потерял у себя, доставка выполнена", "k-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("EXTERNAL_MISSING"))
                .andExpect(jsonPath("$.decision").value("KEEP_OURS"));
    }

    @Test
    void keepOursOnExternalMissingWithoutResultIs409() throws Exception {
        RequestCard request = requests.create("1");
        worker.runOnce();
        UUID problem = check(request);

        resolve(problem, "KEEP_OURS", "x", "k-1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/requests/{id}", request.id()).with(asUser(users, NORTH_OPERATOR_LOGIN)))
                .andExpect(jsonPath("$.actions", contains("RETRY", "RECONCILE")));
    }

    @Test
    void commentRequired() throws Exception {
        RequestCard request = withOurResult();
        carrierHas(request, "TRK-00000002", "700.00");
        UUID problem = check(request);

        resolve(problem, "KEEP_OURS", "  ", "k-1").andExpect(status().isBadRequest());
    }

    @Test
    void secondDecisionOnAClosedProblemIs409() throws Exception {
        RequestCard request = withOurResult();
        carrierHas(request, "TRK-00000002", "700.00");
        UUID problem = check(request);
        resolve(problem, "KEEP_OURS", "x", "k-1").andExpect(status().isOk());

        resolve(problem, "APPLY_CARRIER", "y", "k-2").andExpect(status().isConflict());
        assertThat(result(request)).isEqualTo("SUCCEEDED|TRK-00000001|612.50|1");
    }

    private RequestCard withOurResult() {
        RequestCard request = requests.create("1");
        worker.runOnce();
        results.deliver(succeeded(++feedSeq, request.operationId(), "TRK-00000001", "612.50"));
        return request;
    }

    private JsonNode carrierHas(RequestCard request, String tracking, String price) {
        JsonNode result = com.acme.dispatch.support.TestFeed.entry(++feedSeq, UUID.randomUUID().toString(),
                request.operationId(), 2, "SUCCEEDED", tracking, price, null);
        carrier.lookupAnswer(request.operationId(), FakeCarrier.executed(request.operationId(), result));
        return result;
    }

    /** Checks the request and returns its open problem. */
    private UUID check(RequestCard request) {
        clock.advance(Duration.ofMinutes(1));
        reconciler.check(request.id(), RunType.SCHEDULED, null);
        return jdbc.sql("SELECT id FROM problem WHERE request_id = :id AND status = 'OPEN'")
                .param("id", request.id()).query(UUID.class).single();
    }

    private static JsonNode withoutFeedSeq(JsonNode entry) {
        tools.jackson.databind.node.ObjectNode copy = ((tools.jackson.databind.node.ObjectNode) entry).deepCopy();
        copy.remove("feedSeq");
        return copy;
    }

    private List<String> openProblems(RequestCard request) {
        return jdbc.sql("SELECT kind FROM problem WHERE request_id = :id AND status = 'OPEN'")
                .param("id", request.id()).query(String.class).list();
    }

    /** {@code business_status|tracking_number|price|version}. */
    private String result(RequestCard request) {
        return jdbc.sql("""
                        SELECT business_status || '|' || coalesce(tracking_number, '-') || '|'
                               || coalesce(price::text, '-') || '|' || version
                          FROM delivery_request WHERE id = :id""")
                .param("id", request.id()).query(String.class).single();
    }

    private ResultActions resolve(UUID problem, String decision, String comment, String key) throws Exception {
        return mvc.perform(post("/api/problems/{id}/resolve", problem).header("Idempotency-Key", key)
                .with(csrf()).with(asUser(users, NORTH_OPERATOR_LOGIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\": \"" + decision + "\", \"comment\": \"" + comment + "\"}"));
    }
}
