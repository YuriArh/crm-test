package com.acme.dispatch.problem;

import static com.acme.dispatch.common.SqlTime.toSql;
import static com.acme.dispatch.support.DemoData.NORTH_OPERATOR;
import static com.acme.dispatch.support.DemoData.NORTH_OPERATOR_LOGIN;
import static com.acme.dispatch.support.TestClockConfiguration.START;
import static com.acme.dispatch.support.TestFeed.entry;
import static com.acme.dispatch.support.TestFeed.succeeded;
import static com.acme.dispatch.support.TestSecurity.asUser;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;

import com.acme.dispatch.auth.AppUserRepository;
import com.acme.dispatch.carrier.SubmitOutcome;
import com.acme.dispatch.carrier.SubmitResult;
import com.acme.dispatch.config.IdGenerator;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** {@code GET /api/problems} and the problems of the request card (operator-problems.md §2.5). */
@IntegrationTest
class ProblemQueryTests {

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
    FakeCarrier carrier;
    @Autowired
    ProblemDao problems;
    @Autowired
    IdGenerator ids;
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
    void listsOpenProblemsNewestFirstByDefault() throws Exception {
        UUID oldest = open(ProblemKind.DELIVERY_UNKNOWN, requests.create("1"), START);
        UUID resolved = open(ProblemKind.DELIVERY_UNKNOWN, requests.create("2"), START.plusSeconds(10));
        UUID newest = open(ProblemKind.DELIVERY_FAILED, requests.create("3"), START.plusSeconds(20));
        resolveBySystem(resolved);

        mvc.perform(get("/api/problems").with(asUser(users, NORTH_OPERATOR_LOGIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", contains(newest.toString(), oldest.toString())))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void filtersByStatusAndKind() throws Exception {
        UUID unknown = open(ProblemKind.DELIVERY_UNKNOWN, requests.create("1"), START);
        UUID failed = open(ProblemKind.DELIVERY_FAILED, requests.create("2"), START.plusSeconds(10));
        UUID closed = open(ProblemKind.DELIVERY_UNKNOWN, requests.create("3"), START.plusSeconds(20));
        resolveBySystem(closed);

        mvc.perform(get("/api/problems").param("status", "RESOLVED").with(asUser(users, NORTH_OPERATOR_LOGIN)))
                .andExpect(jsonPath("$.items[*].id", contains(closed.toString())));
        mvc.perform(get("/api/problems").param("status", "ALL").with(asUser(users, NORTH_OPERATOR_LOGIN)))
                .andExpect(jsonPath("$.items[*].id",
                        contains(closed.toString(), failed.toString(), unknown.toString())));
        mvc.perform(get("/api/problems").param("status", "ALL").param("kind", "DELIVERY_UNKNOWN")
                        .with(asUser(users, NORTH_OPERATOR_LOGIN)))
                .andExpect(jsonPath("$.items[*].id", contains(closed.toString(), unknown.toString())));
        mvc.perform(get("/api/problems").param("kind", "DELIVERY_UNKNOWN", "DELIVERY_FAILED")
                        .with(asUser(users, NORTH_OPERATOR_LOGIN)))
                .andExpect(jsonPath("$.items[*].id", contains(failed.toString(), unknown.toString())));
    }

    @Test
    void pagesAndCounts() throws Exception {
        open(ProblemKind.DELIVERY_UNKNOWN, requests.create("1"), START);
        UUID second = open(ProblemKind.DELIVERY_UNKNOWN, requests.create("2"), START.plusSeconds(10));
        open(ProblemKind.DELIVERY_UNKNOWN, requests.create("3"), START.plusSeconds(20));

        mvc.perform(get("/api/problems").param("size", "1").param("page", "1")
                        .with(asUser(users, NORTH_OPERATOR_LOGIN)))
                .andExpect(jsonPath("$.items[*].id", contains(second.toString())))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(3));
    }

    @Test
    void wrongParametersAreRejected() throws Exception {
        for (String[] param : new String[][] {{"size", "101"}, {"size", "0"}, {"page", "-1"}, {"status", "DONE"},
                {"kind", "LOST"}}) {
            mvc.perform(get("/api/problems").param(param[0], param[1]).with(asUser(users, NORTH_OPERATOR_LOGIN)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    void deliveryProblemFields() throws Exception {
        RequestCard request = requests.create("1");
        carrier.answer(new SubmitResult(SubmitOutcome.PERMANENT_ERROR, 400, "HTTP 400"));
        worker.runOnce();

        mvc.perform(get("/api/problems").with(asUser(users, NORTH_OPERATOR_LOGIN)))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].kind").value("DELIVERY_FAILED"))
                .andExpect(jsonPath("$.items[0].status").value("OPEN"))
                .andExpect(jsonPath("$.items[0].details").value("HTTP 400"))
                .andExpect(jsonPath("$.items[0].openedAt").value("2026-11-12T09:00:00Z"))
                .andExpect(jsonPath("$.items[0].resolvedAt").value(nullValue()))
                .andExpect(jsonPath("$.items[0].request.id").value(request.id().toString()))
                .andExpect(jsonPath("$.items[0].request.number").value(request.number()))
                .andExpect(jsonPath("$.items[0].operationId").value(request.operationId().toString()))
                .andExpect(jsonPath("$.items[0].message").value(nullValue()))
                .andExpect(jsonPath("$.items[0].currentResult").value(nullValue()))
                .andExpect(jsonPath("$.items[0].resolvedBy").value(nullValue()))
                .andExpect(jsonPath("$.items[0].decision").value(nullValue()))
                .andExpect(jsonPath("$.items[0].comment").value(nullValue()))
                .andExpect(jsonPath("$.items[0].actions", hasSize(0)));
    }

    @Test
    void invalidMessageShowsWhatCouldBeRead() throws Exception {
        RequestCard request = requests.create("1");
        String messageId = UUID.randomUUID().toString();
        results.deliver(entry(7, messageId, request.operationId(), 2, "SUCCEEDED", "TRK-00000001", "-1.00", null));

        mvc.perform(get("/api/problems").with(asUser(users, NORTH_OPERATOR_LOGIN)))
                .andExpect(jsonPath("$.items[0].kind").value("INVALID_MESSAGE"))
                .andExpect(jsonPath("$.items[0].request.id").value(request.id().toString()))
                .andExpect(jsonPath("$.items[0].operationId").value(request.operationId().toString()))
                .andExpect(jsonPath("$.items[0].message.messageId").value(messageId))
                .andExpect(jsonPath("$.items[0].message.feedSeq").value(7))
                .andExpect(jsonPath("$.items[0].message.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.items[0].message.sequence").value(2))
                .andExpect(jsonPath("$.items[0].message.trackingNumber").value("TRK-00000001"))
                .andExpect(jsonPath("$.items[0].message.price").value(-1.0))
                .andExpect(jsonPath("$.items[0].message.occurredAt").value("2026-11-12T09:00:05Z"))
                .andExpect(jsonPath("$.items[0].message.payloadExcerpt", containsString("\"price\":-1.00")))
                .andExpect(jsonPath("$.items[0].currentResult").value(nullValue()))
                .andExpect(jsonPath("$.items[0].actions", contains("ACKNOWLEDGED")));
    }

    @Test
    void unreadableMessageHasOnlyItsExcerpt() throws Exception {
        String reason = "x\u0001".repeat(1500);
        results.deliver(com.acme.dispatch.support.TestFeed.raw(
                "{\"feedSeq\": 3, \"operationId\": \"not-a-uuid\", \"reason\": \"" + reason.replace("\u0001", "\\u0001")
                        + "\"}"));

        mvc.perform(get("/api/problems").with(asUser(users, NORTH_OPERATOR_LOGIN)))
                .andExpect(jsonPath("$.items[0].kind").value("INVALID_MESSAGE"))
                .andExpect(jsonPath("$.items[0].request").value(nullValue()))
                .andExpect(jsonPath("$.items[0].operationId").value(nullValue()))
                .andExpect(jsonPath("$.items[0].message.messageId").value(nullValue()))
                .andExpect(jsonPath("$.items[0].message.sequence").value(nullValue()))
                .andExpect(jsonPath("$.items[0].message.payloadExcerpt", matchesRegex("(?s).{1,1999}…")))
                .andExpect(jsonPath("$.items[0].message.payloadExcerpt", not(containsString("\u0001"))));
    }

    @Test
    void resultConflictShowsBothResults() throws Exception {
        RequestCard request = requests.create("1");
        results.deliver(succeeded(1, request.operationId(), "TRK-00000001", "325.00"));
        results.deliver(succeeded(2, request.operationId(), "TRK-00000002", "400.00"));

        mvc.perform(get("/api/problems").with(asUser(users, NORTH_OPERATOR_LOGIN)))
                .andExpect(jsonPath("$.items[0].kind").value("RESULT_CONFLICT"))
                .andExpect(jsonPath("$.items[0].message.trackingNumber").value("TRK-00000002"))
                .andExpect(jsonPath("$.items[0].message.price").value(400.0))
                .andExpect(jsonPath("$.items[0].currentResult.businessStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.items[0].currentResult.result.trackingNumber").value("TRK-00000001"))
                .andExpect(jsonPath("$.items[0].currentResult.result.price").value(325.0))
                .andExpect(jsonPath("$.items[0].actions", contains("APPLY_CARRIER", "KEEP_OURS")));
    }

    @Test
    void conflictWithAnIntermediateRecordCanOnlyBeKept() throws Exception {
        RequestCard request = requests.create("1");
        results.deliver(succeeded(1, request.operationId(), "TRK-00000001", "325.00"));
        results.deliver(entry(2, UUID.randomUUID().toString(), request.operationId(), 3, "IN_PROGRESS", null, null,
                null));

        mvc.perform(get("/api/problems").with(asUser(users, NORTH_OPERATOR_LOGIN)))
                .andExpect(jsonPath("$.items[0].kind").value("RESULT_CONFLICT"))
                .andExpect(jsonPath("$.items[0].actions", contains("KEEP_OURS")));
    }

    @Test
    void resolvedProblemShowsWhoAndHow() throws Exception {
        RequestCard request = requests.create("1");
        results.deliver(succeeded(1, request.operationId(), "TRK-00000001", "325.00"));
        results.deliver(succeeded(2, request.operationId(), "TRK-00000002", "400.00"));
        jdbc.sql("""
                        UPDATE problem SET status = 'RESOLVED', resolved_at = :at, resolved_by = :by,
                               decision = 'KEEP_OURS', comment = 'Checked with the carrier'""")
                .param("at", toSql(START.plusSeconds(30)))
                .param("by", NORTH_OPERATOR)
                .update();
        UUID bySystem = open(ProblemKind.DELIVERY_UNKNOWN, request, START.plusSeconds(40));
        resolveBySystem(bySystem);

        mvc.perform(get("/api/problems").param("status", "RESOLVED").with(asUser(users, NORTH_OPERATOR_LOGIN)))
                .andExpect(jsonPath("$.items[0].resolvedBy").value(nullValue()))
                .andExpect(jsonPath("$.items[0].decision").value("SYSTEM"))
                .andExpect(jsonPath("$.items[1].resolvedAt").value("2026-11-12T09:00:30Z"))
                .andExpect(jsonPath("$.items[1].resolvedBy.id").value(NORTH_OPERATOR.toString()))
                .andExpect(jsonPath("$.items[1].resolvedBy.displayName").value("Оператор (Север)"))
                .andExpect(jsonPath("$.items[1].decision").value("KEEP_OURS"))
                .andExpect(jsonPath("$.items[1].comment").value("Checked with the carrier"))
                .andExpect(jsonPath("$.items[1].actions", hasSize(0)));
    }

    @Test
    void cardShowsProblemsAndActionsToOperator() throws Exception {
        RequestCard request = requests.create("1");
        carrier.answer(new SubmitResult(SubmitOutcome.UNKNOWN, null, "No response in time"));
        worker.runOnce();
        clock.advance(Duration.ofSeconds(7));
        carrier.answer(new SubmitResult(SubmitOutcome.PERMANENT_ERROR, 400, "HTTP 400"));
        worker.runOnce();

        mvc.perform(get("/api/requests/{id}", request.id()).with(asUser(users, NORTH_OPERATOR_LOGIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.problems[*].kind", contains("DELIVERY_FAILED", "DELIVERY_UNKNOWN")))
                .andExpect(jsonPath("$.problems[*].request.id",
                        containsInAnyOrder(request.id().toString(), request.id().toString())))
                .andExpect(jsonPath("$.actions", contains("RETRY")));
    }

    @Test
    void acknowledgedRequestHasNoActions() throws Exception {
        RequestCard request = requests.create("1");
        worker.runOnce();

        mvc.perform(get("/api/requests/{id}", request.id()).with(asUser(users, NORTH_OPERATOR_LOGIN)))
                .andExpect(jsonPath("$.problems", hasSize(0)))
                .andExpect(jsonPath("$.actions", hasSize(0)));
    }

    private UUID open(ProblemKind kind, RequestCard request, java.time.Instant at) {
        UUID id = ids.newId();
        problems.open(id, kind, request.id(), null, "why", at);
        return id;
    }

    private void resolveBySystem(UUID id) {
        jdbc.sql("UPDATE problem SET status = 'RESOLVED', resolved_at = opened_at, decision = 'SYSTEM' WHERE id = :id")
                .param("id", id)
                .update();
    }
}
