package com.acme.dispatch.problem;

import static com.acme.dispatch.support.DemoData.NORTH_OPERATOR;
import static com.acme.dispatch.support.DemoData.NORTH_OPERATOR_LOGIN;
import static com.acme.dispatch.support.TestClockConfiguration.START;
import static com.acme.dispatch.support.TestFeed.entry;
import static com.acme.dispatch.support.TestFeed.succeeded;
import static com.acme.dispatch.support.TestSecurity.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.acme.dispatch.auth.AppUserRepository;
import com.acme.dispatch.exchange.inbox.InboxProcessor;
import com.acme.dispatch.request.RequestCard;
import com.acme.dispatch.support.IntegrationTest;
import com.acme.dispatch.support.MutableClock;
import com.acme.dispatch.support.OurSide;
import com.acme.dispatch.support.TestEvents;
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
import org.springframework.test.web.servlet.ResultActions;

/** Решение по конфликту и «Разобрано» (operator-problems.md §2.4). */
@IntegrationTest
class ResolveProblemTests {

    @Autowired
    MockMvc mvc;
    @Autowired
    AppUserRepository users;
    @Autowired
    TestRequests requests;
    @Autowired
    TestResults results;
    @Autowired
    InboxProcessor processor;
    @Autowired
    MutableClock clock;
    @Autowired
    JdbcClient jdbc;

    /** Feed positions are unique; each test numbers its own entries. */
    long feedSeq;

    @BeforeEach
    void setUp() {
        clock.set(START);
        feedSeq = 100;
    }

    @AfterEach
    void cleanUp() {
        TestRows.deleteRequestData(jdbc);
    }

    @Test
    void keepOursClosesWithoutChangingTheResult() throws Exception {
        Conflict conflict = conflict();
        clock.set(START.plusSeconds(30));

        resolve(conflict.problem(), "KEEP_OURS", "  Checked with the carrier  ", "k-1")
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.id").value(conflict.problem().toString()))
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.decision").value("KEEP_OURS"))
                .andExpect(jsonPath("$.comment").value("Checked with the carrier"))
                .andExpect(jsonPath("$.resolvedBy.id").value(NORTH_OPERATOR.toString()))
                .andExpect(jsonPath("$.resolvedAt").value("2026-11-12T09:00:30Z"))
                .andExpect(jsonPath("$.actions", hasSize(0)));

        assertThat(result(conflict.request())).isEqualTo("SUCCEEDED|TRK-00000001|325.00|1");
    }

    @Test
    void applyCarrierReplacesTheResult() throws Exception {
        Conflict conflict = conflict();

        resolve(conflict.problem(), "APPLY_CARRIER", "The carrier confirmed the new number", "k-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("APPLY_CARRIER"))
                .andExpect(jsonPath("$.currentResult.result.trackingNumber").value("TRK-00000002"))
                .andExpect(jsonPath("$.message.trackingNumber").value("TRK-00000002"))
                .andExpect(jsonPath("$.ourSide.result.trackingNumber").value("TRK-00000001"))
                .andExpect(jsonPath("$.ourSide.result.price").value(325.0));

        assertThat(result(conflict.request())).isEqualTo("SUCCEEDED|TRK-00000002|400.00|2");
        assertThat(jdbc.sql("""
                        SELECT last_applied_seq || '|' || applied_inbox_id FROM dispatch_operation
                         WHERE request_id = :id""")
                .param("id", conflict.request().id())
                .query(String.class).single()).isEqualTo("2|" + conflict.record());
        assertThat(results.status(conflict.record())).as("the outcome of processing is history").isEqualTo("CONFLICT");
        assertThat(OurSide.of(jdbc, conflict.problem())).as("our result before the decision")
                .isEqualTo("SUCCEEDED/TRK-00000001/325.00/-");
    }

    @Test
    void applyCarrierIsRecorded() throws Exception {
        Conflict conflict = conflict();

        resolve(conflict.problem(), "APPLY_CARRIER", "Confirmed", "k-1").andExpect(status().isOk());

        assertThat(TestEvents.of(jdbc, conflict.request().id())).last().isEqualTo("RESULT_CHANGED v2 by "
                + NORTH_OPERATOR + " [trackingNumber: TRK-00000001 → TRK-00000002, price: 325 → 400]");
        assertThat(TestEvents.links(jdbc, conflict.request().id())).last()
                .isEqualTo(conflict.record() + "/" + conflict.problem());
    }

    @Test
    void appliedRecordFromTheFeedAgainIsStale() throws Exception {
        Conflict conflict = conflict();
        resolve(conflict.problem(), "APPLY_CARRIER", "Confirmed", "k-1").andExpect(status().isOk());

        assertThat(results.deliver(succeeded(++feedSeq, conflict.request().operationId(), "TRK-00000002", "400.00")))
                .isEqualTo("STALE");
        assertThat(jdbc.sql("SELECT count(*) FROM problem").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void applyCarrierNeedsAFinalRecord() throws Exception {
        RequestCard request = requests.create("1");
        results.deliver(succeeded(1, request.operationId(), "TRK-00000001", "325.00"));
        results.deliver(entry(2, UUID.randomUUID().toString(), request.operationId(), 3, "IN_PROGRESS", null, null,
                null));
        UUID problem = onlyProblem();

        resolve(problem, "APPLY_CARRIER", "Roll back?", "k-1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        assertThat(result(request)).isEqualTo("SUCCEEDED|TRK-00000001|325.00|1");
    }

    @Test
    void editStartedBeforeTheDecisionConflicts() throws Exception {
        Conflict conflict = conflict();
        resolve(conflict.problem(), "APPLY_CARRIER", "Confirmed", "k-1").andExpect(status().isOk());

        mvc.perform(patch("/api/requests/{id}", conflict.request().id()).with(csrf())
                        .with(asUser(users, NORTH_OPERATOR_LOGIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": 1, \"comment\": \"late\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    void acknowledgeClosesMessageProblems() throws Exception {
        RequestCard request = requests.create("1");
        results.deliver(entry(1, UUID.randomUUID().toString(), request.operationId(), 2, "SUCCEEDED",
                "TRK-00000001", "-1.00", null));
        results.deliver(succeeded(2, UUID.randomUUID(), "TRK-00000001", "325.00"));

        for (String id : jdbc.sql("SELECT id::text FROM problem").query(String.class).list()) {
            resolve(UUID.fromString(id), "ACKNOWLEDGED", "Reported to the carrier", "k-" + id)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("RESOLVED"));
        }
        assertThat(jdbc.sql("SELECT count(*) FROM problem WHERE status = 'OPEN'").query(Long.class).single()).isZero();
    }

    @Test
    void decisionMustFitTheKind() throws Exception {
        Conflict conflict = conflict();
        UUID delivery = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO problem (id, kind, status, request_id, details, opened_at, our_recorded)
                        VALUES (:id, 'DELIVERY_FAILED', 'OPEN', :request, 'HTTP 400', now(), false)""")
                .param("id", delivery)
                .param("request", conflict.request().id())
                .update();

        for (String decision : List.of("ACKNOWLEDGED", "RETRIED", "SYSTEM")) {
            resolve(conflict.problem(), decision, "x", "k-" + decision)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        }
        resolve(delivery, "KEEP_OURS", "x", "k-delivery")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }

    @Test
    void resolvedProblemCannotBeResolvedAgain() throws Exception {
        Conflict conflict = conflict();
        resolve(conflict.problem(), "KEEP_OURS", "First", "k-1").andExpect(status().isOk());

        resolve(conflict.problem(), "APPLY_CARRIER", "Second", "k-2")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        assertThat(jdbc.sql("SELECT decision || '|' || comment FROM problem").query(String.class).single())
                .isEqualTo("KEEP_OURS|First");
    }

    @Test
    void concurrentDecisionsOneWins() throws Exception {
        Conflict conflict = conflict();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Callable<Integer>> calls = List.of(
                    () -> resolve(conflict.problem(), "KEEP_OURS", "One", "k-1").andReturn().getResponse().getStatus(),
                    () -> resolve(conflict.problem(), "APPLY_CARRIER", "Two", "k-2").andReturn().getResponse()
                            .getStatus());
            for (Future<Integer> done : pool.invokeAll(calls)) {
                statuses.add(done.get());
            }
        } finally {
            pool.shutdown();
        }

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.sql("SELECT count(*) FROM problem WHERE status = 'RESOLVED'").query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    void bodyIsValidated() throws Exception {
        Conflict conflict = conflict();

        for (String body : List.of("{\"decision\": \"KEEP_OURS\", \"comment\": \"   \"}",
                "{\"decision\": \"KEEP_OURS\"}",
                "{\"decision\": \"KEEP_OURS\", \"comment\": \"" + "x".repeat(2001) + "\"}",
                "{\"comment\": \"x\"}",
                "{\"decision\": \"MAYBE\", \"comment\": \"x\"}")) {
            mvc.perform(post("/api/problems/{id}/resolve", conflict.problem()).header("Idempotency-Key", "k-" + body)
                            .with(csrf()).with(asUser(users, NORTH_OPERATOR_LOGIN))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        assertThat(jdbc.sql("SELECT status FROM problem").query(String.class).single()).isEqualTo("OPEN");
    }

    @Test
    void sameKeyReplays() throws Exception {
        Conflict conflict = conflict();
        String first = resolve(conflict.problem(), "KEEP_OURS", "Checked", "k-1").andReturn().getResponse()
                .getContentAsString();

        String second = resolve(conflict.problem(), "KEEP_OURS", "Checked", "k-1")
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
    }

    @Test
    void sameKeyForAnotherProblemIsRejected() throws Exception {
        Conflict one = conflict();
        Conflict other = conflict();
        resolve(one.problem(), "KEEP_OURS", "Checked", "k-1").andExpect(status().isOk());

        resolve(other.problem(), "KEEP_OURS", "Checked", "k-1")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void keyIsRequired() throws Exception {
        Conflict conflict = conflict();

        mvc.perform(post("/api/problems/{id}/resolve", conflict.problem()).with(csrf())
                        .with(asUser(users, NORTH_OPERATOR_LOGIN)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\": \"KEEP_OURS\", \"comment\": \"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void unknownOrMalformedProblemIsNotFound() throws Exception {
        resolve(UUID.randomUUID(), "KEEP_OURS", "x", "k-1").andExpect(status().isNotFound());
        mvc.perform(post("/api/problems/{id}/resolve", "not-an-id").header("Idempotency-Key", "k-2").with(csrf())
                        .with(asUser(users, NORTH_OPERATOR_LOGIN)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\": \"KEEP_OURS\", \"comment\": \"x\"}"))
                .andExpect(status().isNotFound());
    }

    /** A request with result A applied and a disputed final record B of the same sequence. */
    private Conflict conflict() {
        RequestCard request = requests.create("1");
        results.deliver(succeeded(++feedSeq, request.operationId(), "TRK-00000001", "325.00"));
        UUID record = results.receive(succeeded(++feedSeq, request.operationId(), "TRK-00000002", "400.00"))
                .orElseThrow();
        processor.processDue();
        assertThat(results.status(record)).isEqualTo("CONFLICT");
        UUID problem = jdbc.sql("SELECT id FROM problem WHERE inbox_id = :id").param("id", record)
                .query(UUID.class).single();
        return new Conflict(request, record, problem);
    }

    private UUID onlyProblem() {
        return jdbc.sql("SELECT id FROM problem").query(UUID.class).single();
    }

    private ResultActions resolve(UUID problem, String decision, String comment, String key) throws Exception {
        return mvc.perform(post("/api/problems/{id}/resolve", problem).header("Idempotency-Key", key)
                .with(csrf()).with(asUser(users, NORTH_OPERATOR_LOGIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\": \"" + decision + "\", \"comment\": \"" + comment + "\"}"));
    }

    /** {@code business_status|tracking_number|price|version}. */
    private String result(RequestCard request) {
        return jdbc.sql("""
                        SELECT business_status || '|' || coalesce(tracking_number, '-') || '|'
                               || coalesce(price::text, '-') || '|' || version
                          FROM delivery_request WHERE id = :id""")
                .param("id", request.id())
                .query(String.class)
                .single();
    }

    private record Conflict(RequestCard request, UUID record, UUID problem) {
    }
}
