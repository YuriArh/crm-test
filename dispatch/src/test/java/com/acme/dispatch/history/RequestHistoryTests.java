package com.acme.dispatch.history;

import static com.acme.dispatch.support.DemoData.NORTH_INITIATOR;
import static com.acme.dispatch.support.DemoData.NORTH_INITIATOR_LOGIN;
import static com.acme.dispatch.support.DemoData.NORTH_OPERATOR;
import static com.acme.dispatch.support.DemoData.NORTH_OPERATOR_LOGIN;
import static com.acme.dispatch.support.DemoData.SOUTH_INITIATOR_LOGIN;
import static com.acme.dispatch.support.TestClockConfiguration.START;
import static com.acme.dispatch.support.TestFeed.atFeedSeq;
import static com.acme.dispatch.support.TestFeed.entry;
import static com.acme.dispatch.support.TestFeed.inProgress;
import static com.acme.dispatch.support.TestFeed.succeeded;
import static com.acme.dispatch.support.TestSecurity.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.acme.dispatch.auth.AppUserRepository;
import com.acme.dispatch.carrier.SubmitOutcome;
import com.acme.dispatch.carrier.SubmitResult;
import com.acme.dispatch.exchange.SendWorker;
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
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** The history of a request: one feed of its changes and its exchange, by role (docs/specs/request-history.md). */
@IntegrationTest
class RequestHistoryTests {

    static final SubmitResult TEMPORARY = new SubmitResult(SubmitOutcome.TEMPORARY_ERROR, 503, "HTTP 503");
    static final JsonMapper JSON = JsonMapper.builder().build();

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
    void operatorSeesTheWholeHistory() throws Exception {
        RequestCard request = wholeLife();

        history(request.id(), NORTH_OPERATOR_LOGIN, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].kind").value(contains("EDITED", "PROBLEM_RESOLVED", "PROBLEM_OPENED",
                        "RECONCILIATION", "RESULT_CHANGED", "RESULT_RECEIVED", "RESULT_CHANGED", "RESULT_RECEIVED",
                        "DELIVERY_ACKNOWLEDGED", "SEND_ATTEMPT", "SEND_ATTEMPT", "CREATED")))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()))
                .andExpect(jsonPath("$.items[0].actor.id").value(NORTH_INITIATOR.toString()))
                .andExpect(jsonPath("$.items[0].version").value(3))
                .andExpect(jsonPath("$.items[0].changes[0].field").value("comment"))
                .andExpect(jsonPath("$.items[0].changes[0].to").value("Call first"))
                .andExpect(jsonPath("$.items[1].actor.displayName").value("Оператор (Север)"))
                .andExpect(jsonPath("$.items[1].decision").value("KEEP_OURS"))
                .andExpect(jsonPath("$.items[1].comment").value("Ours is right"))
                .andExpect(jsonPath("$.items[2].problemKind").value("RECONCILIATION_CONFLICT"))
                .andExpect(jsonPath("$.items[3].outcome").value("CONFLICT"))
                .andExpect(jsonPath("$.items[3].trigger").value("SCHEDULED"))
                .andExpect(jsonPath("$.items[4].actor").value(nullValue()))
                .andExpect(jsonPath("$.items[4].changes[*].field")
                        .value(contains("businessStatus", "trackingNumber", "price")))
                .andExpect(jsonPath("$.items[4].changes[2].to").value("612.5"))
                .andExpect(jsonPath("$.items[4].recordId").isString())
                .andExpect(jsonPath("$.items[5].processingStatus").value("APPLIED"))
                .andExpect(jsonPath("$.items[8].via").value("RESPONSE"))
                .andExpect(jsonPath("$.items[9].attemptNo").value(2))
                .andExpect(jsonPath("$.items[9].outcome").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.items[10].outcome").value("TEMPORARY_ERROR"))
                .andExpect(jsonPath("$.items[10].httpStatus").value(503))
                .andExpect(jsonPath("$.items[10].error").value("HTTP 503"))
                .andExpect(jsonPath("$.items[11].actor.id").value(NORTH_INITIATOR.toString()))
                .andExpect(jsonPath("$.items[11].at").value("2026-11-12T09:00:00Z"));
    }

    @Test
    void initiatorSeesTheBusinessPart() throws Exception {
        RequestCard request = wholeLife();

        history(request.id(), NORTH_INITIATOR_LOGIN, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].kind").value(contains("EDITED", "RESULT_CHANGED", "RESULT_CHANGED",
                        "DELIVERY_ACKNOWLEDGED", "CREATED")))
                .andExpect(jsonPath("$.items[1].recordId").value(nullValue()))
                .andExpect(jsonPath("$.items[1].problemId").value(nullValue()))
                .andExpect(jsonPath("$.items[3].via").value(nullValue()));
    }

    @Test
    void resultReceivedShowsDuplicates() throws Exception {
        RequestCard request = requests.create("1");
        JsonNode result = succeeded(1, request.operationId(), "TRK-00000001", "612.50");
        results.deliver(result);
        clock.advance(Duration.ofMinutes(1));
        results.deliver(atFeedSeq(result, 2));
        results.deliver(atFeedSeq(result, 3));

        String received = "$.items[?(@.kind == 'RESULT_RECEIVED')]";
        history(request.id(), NORTH_OPERATOR_LOGIN, "")
                .andExpect(jsonPath("$.items[*].kind").value(contains("DELIVERY_ACKNOWLEDGED", "RESULT_CHANGED",
                        "RESULT_RECEIVED", "CREATED")))
                .andExpect(jsonPath(received, hasSize(1)))
                .andExpect(jsonPath(received + ".source").value(contains("FEED")))
                .andExpect(jsonPath(received + ".resultStatus").value(contains("SUCCEEDED")))
                .andExpect(jsonPath(received + ".sequence").value(contains(2)))
                .andExpect(jsonPath(received + ".duplicates").value(contains(2)))
                .andExpect(jsonPath(received + ".lastDuplicateAt").value(contains("2026-11-12T09:01:00Z")));
    }

    @Test
    void resultReceivedIsAtItsProcessing() throws Exception {
        RequestCard request = requests.create("1");
        UUID record = results.receive(inProgress(1, request.operationId())).orElseThrow();
        clock.advance(Duration.ofMinutes(2));
        results.deliver(succeeded(2, request.operationId(), "TRK-00000001", "612.50"));

        history(request.id(), NORTH_OPERATOR_LOGIN, "")
                .andExpect(jsonPath("$.items[?(@.recordId == '" + record + "' && @.kind == 'RESULT_RECEIVED')].at")
                        .value(contains("2026-11-12T09:02:00Z")))
                .andExpect(jsonPath("$.items[?(@.recordId == '" + record + "' && @.kind == 'RESULT_RECEIVED')]"
                        + ".receivedAt").value(contains("2026-11-12T09:00:00Z")));

        // A record older than the history has no processing time: it shows when it was received.
        jdbc.sql("UPDATE inbox SET processed_at = NULL WHERE id = :id").param("id", record).update();
        history(request.id(), NORTH_OPERATOR_LOGIN, "")
                .andExpect(jsonPath("$.items[?(@.recordId == '" + record + "' && @.kind == 'RESULT_RECEIVED')].at")
                        .value(contains("2026-11-12T09:00:00Z")));
    }

    @Test
    void editShowsPeople() throws Exception {
        RequestCard request = requests.create("1");
        clock.advance(Duration.ofMinutes(1));
        mvc.perform(patch("/api/requests/{id}", request.id()).with(csrf()).with(asUser(users, NORTH_OPERATOR_LOGIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": 0, \"assigneeId\": \"" + NORTH_OPERATOR + "\"}"))
                .andExpect(status().isOk());

        history(request.id(), NORTH_INITIATOR_LOGIN, "")
                .andExpect(jsonPath("$.items[0].kind").value("EDITED"))
                .andExpect(jsonPath("$.items[0].changes[0].field").value("assignee"))
                .andExpect(jsonPath("$.items[0].changes[0].from").value(nullValue()))
                .andExpect(jsonPath("$.items[0].changes[0].to.id").value(NORTH_OPERATOR.toString()))
                .andExpect(jsonPath("$.items[0].changes[0].to.displayName").value("Оператор (Север)"));
    }

    @Test
    void paging() throws Exception {
        RequestCard request = wholeLife();
        List<String> all = kinds(history(request.id(), NORTH_OPERATOR_LOGIN, ""));

        List<String> paged = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = JSON.readTree(history(request.id(), NORTH_OPERATOR_LOGIN,
                    "limit=5" + (cursor == null ? "" : "&before=" + cursor))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            page.get("items").forEach(item -> paged.add(item.get("kind").asString() + "/" + item.get("id")
                    .asString()));
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asString();
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(paged).isEqualTo(all);
    }

    @Test
    void limitAndCursorAreChecked() throws Exception {
        RequestCard request = requests.create("1");

        for (String query : List.of("limit=0", "limit=201", "limit=x", "before=broken")) {
            history(request.id(), NORTH_OPERATOR_LOGIN, query)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    void foreignRequestIsNotFound() throws Exception {
        RequestCard north = requests.create("1");
        RequestCard south = requests.createAs(SOUTH_INITIATOR_LOGIN, "Pallets", "1", "South st 1");

        history(north.id(), SOUTH_INITIATOR_LOGIN, "").andExpect(status().isNotFound());
        history(south.id(), NORTH_OPERATOR_LOGIN, "").andExpect(status().isNotFound());
        history(UUID.randomUUID(), NORTH_OPERATOR_LOGIN, "").andExpect(status().isNotFound());
        mvc.perform(get("/api/requests/not-an-id/history").with(asUser(users, NORTH_OPERATOR_LOGIN)))
                .andExpect(status().isNotFound());
    }

    @Test
    void anonymousIsUnauthorized() throws Exception {
        RequestCard request = requests.create("1");

        mvc.perform(get("/api/requests/{id}/history", request.id())).andExpect(status().isUnauthorized());
    }

    /**
     * Created; a temporary error, then acknowledged; IN_PROGRESS; SUCCEEDED and its repeat; a reconciliation
     * conflict; the operator keeps ours; the initiator comments. A minute between the steps.
     */
    private RequestCard wholeLife() throws Exception {
        RequestCard request = requests.create("1");
        UUID operationId = request.operationId();
        carrier.answer(TEMPORARY);
        step();
        worker.runOnce();
        step();
        worker.runOnce();
        step();
        results.deliver(inProgress(1, operationId));
        step();
        JsonNode result = succeeded(2, operationId, "TRK-00000001", "612.50");
        results.deliver(result);
        results.deliver(atFeedSeq(result, 3));
        step();
        carrier.lookupAnswer(operationId, FakeCarrier.executed(operationId,
                entry(4, UUID.randomUUID().toString(), operationId, 2, "SUCCEEDED", "TRK-00000002", "700.00", null)));
        reconciler.check(request.id(), RunType.SCHEDULED, null);
        UUID problem = jdbc.sql("SELECT id FROM problem WHERE request_id = :id AND status = 'OPEN'")
                .param("id", request.id()).query(UUID.class).single();
        step();
        mvc.perform(post("/api/problems/{id}/resolve", problem).header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(csrf()).with(asUser(users, NORTH_OPERATOR_LOGIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\": \"KEEP_OURS\", \"comment\": \"Ours is right\"}"))
                .andExpect(status().isOk());
        step();
        mvc.perform(patch("/api/requests/{id}", request.id()).with(csrf()).with(asUser(users, NORTH_INITIATOR_LOGIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\": 2, \"comment\": \"Call first\"}"))
                .andExpect(status().isOk());
        return request;
    }

    private ResultActions history(UUID requestId, String login, String query) throws Exception {
        return mvc.perform(get("/api/requests/" + requestId + "/history" + (query.isEmpty() ? "" : "?" + query))
                .with(asUser(users, login)));
    }

    private List<String> kinds(ResultActions result) throws Exception {
        List<String> kinds = new ArrayList<>();
        JSON.readTree(result.andReturn().getResponse().getContentAsString()).get("items")
                .forEach(item -> kinds.add(item.get("kind").asString() + "/" + item.get("id").asString()));
        return kinds;
    }

    private void step() {
        clock.advance(Duration.ofMinutes(1));
    }
}
