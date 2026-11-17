package com.acme.dispatch.history;

import static com.acme.dispatch.support.DemoData.NORTH_INITIATOR_LOGIN;
import static com.acme.dispatch.support.DemoData.NORTH_OPERATOR;
import static com.acme.dispatch.support.DemoData.NORTH_OPERATOR_LOGIN;
import static com.acme.dispatch.support.TestClockConfiguration.START;
import static com.acme.dispatch.support.TestFeed.entry;
import static com.acme.dispatch.support.TestFeed.inProgress;
import static com.acme.dispatch.support.TestFeed.succeeded;
import static com.acme.dispatch.support.TestSecurity.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The request row is the current state and its events are its changes (docs/specs/request-history.md §2.1): every
 * version after creation has exactly one event, and the state at creation plus the events in version order give the
 * current state. A writer that changes a request without its event shows up here as a gap.
 */
@IntegrationTest
class RequestHistoryInvariantTests {

    static final SubmitResult TIMEOUT = new SubmitResult(SubmitOutcome.UNKNOWN, null, "No response in time");

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

    RequestCard request;

    @BeforeEach
    void wholeLife() throws Exception {
        clock.set(START);
        carrier.reset();
        request = requests.create("1");
        UUID operationId = request.operationId();

        carrier.answer(TIMEOUT);
        worker.runOnce();
        step();
        mvc.perform(post("/api/requests/{id}/retry", request.id()).header("Idempotency-Key", "retry-1")
                .with(csrf()).with(asUser(users, NORTH_OPERATOR_LOGIN))).andExpect(status().isOk());
        worker.runOnce();
        step();
        edit(NORTH_INITIATOR_LOGIN, 0, "{\"comment\": \"Call first\"}");
        edit(NORTH_OPERATOR_LOGIN, 1, "{\"assigneeId\": \"" + NORTH_OPERATOR + "\"}");
        step();
        results.deliver(inProgress(1, operationId));
        results.deliver(succeeded(2, operationId, "TRK-00000001", "612.50"));
        step();
        carrier.lookupAnswer(operationId, FakeCarrier.executed(operationId,
                entry(3, UUID.randomUUID().toString(), operationId, 2, "SUCCEEDED", "TRK-00000002", "700.00", null)));
        reconciler.check(request.id(), RunType.SCHEDULED, null);
        UUID problem = jdbc.sql("SELECT id FROM problem WHERE request_id = :id AND status = 'OPEN'")
                .param("id", request.id()).query(UUID.class).single();
        step();
        mvc.perform(post("/api/problems/{id}/resolve", problem).header("Idempotency-Key", "resolve-1")
                        .with(csrf()).with(asUser(users, NORTH_OPERATOR_LOGIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\": \"APPLY_CARRIER\", \"comment\": \"Confirmed\"}"))
                .andExpect(status().isOk());
        step();
        edit(NORTH_OPERATOR_LOGIN, 5, "{\"comment\": \"Price confirmed\", \"assigneeId\": null}");
    }

    @AfterEach
    void cleanUp() {
        TestRows.deleteRequestData(jdbc);
    }

    @Test
    void versionsHaveNoGaps() {
        long current = jdbc.sql("SELECT version FROM delivery_request WHERE id = :id")
                .param("id", request.id()).query(Long.class).single();

        List<Long> versions = jdbc.sql("""
                        SELECT version FROM request_event WHERE request_id = :id AND version IS NOT NULL
                         ORDER BY version""")
                .param("id", request.id()).query(Long.class).list();

        assertThat(current).as("comment, assignee, IN_PROGRESS, SUCCEEDED, the operator's result, the last edit")
                .isEqualTo(6);
        assertThat(versions).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
    }

    @Test
    void replayGivesCurrentState() {
        Map<String, String> state = new HashMap<>();
        state.put("comment", null);
        state.put("assigneeId", null);
        state.put("businessStatus", "AWAITING");
        state.put("trackingNumber", null);
        state.put("price", null);
        state.put("rejectionReason", null);

        for (List<Change> changes : versionedChanges()) {
            for (Change change : changes) {
                assertThat(state).as("the event starts from the state before it").containsKey(change.field());
                assertThat(state.get(change.field())).as(change.field() + " before the change")
                        .isEqualTo(change.from());
                state.put(change.field(), change.to());
            }
        }

        assertThat(state).isEqualTo(currentState());
    }

    @Test
    void retriesDoNotTakeVersions() {
        assertThat(jdbc.sql("""
                        SELECT kind || ':' || coalesce(version::text, '-') FROM request_event
                         WHERE request_id = :id AND kind = 'DELIVERY_RETRIED'""")
                .param("id", request.id()).query(String.class).list()).containsExactly("DELIVERY_RETRIED:-");
    }

    private List<List<Change>> versionedChanges() {
        List<List<Change>> all = new ArrayList<>();
        jdbc.sql("""
                        SELECT changes::text FROM request_event WHERE request_id = :id AND version IS NOT NULL
                         ORDER BY version""")
                .param("id", request.id())
                .query(String.class)
                .list()
                .forEach(json -> all.add(Change.listOf(json)));
        return all;
    }

    private Map<String, String> currentState() {
        return jdbc.sql("""
                        SELECT comment, assignee_id, business_status, tracking_number, price, rejection_reason
                          FROM delivery_request WHERE id = :id""")
                .param("id", request.id())
                .query((rs, row) -> {
                    Map<String, String> state = new HashMap<>();
                    state.put("comment", rs.getString("comment"));
                    UUID assignee = rs.getObject("assignee_id", UUID.class);
                    state.put("assigneeId", assignee == null ? null : assignee.toString());
                    state.put("businessStatus", rs.getString("business_status"));
                    state.put("trackingNumber", rs.getString("tracking_number"));
                    BigDecimal price = rs.getBigDecimal("price");
                    state.put("price", price == null ? null : price.stripTrailingZeros().toPlainString());
                    state.put("rejectionReason", rs.getString("rejection_reason"));
                    return state;
                })
                .single();
    }

    private void edit(String login, long version, String fields) throws Exception {
        String body = "{\"version\": " + version + ", " + fields.substring(1);
        mvc.perform(patch("/api/requests/{id}", request.id()).with(csrf()).with(asUser(users, login))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    /** Checks of one operation are ordered by time; the clock is frozen otherwise. */
    private void step() {
        clock.advance(Duration.ofMinutes(1));
    }
}
