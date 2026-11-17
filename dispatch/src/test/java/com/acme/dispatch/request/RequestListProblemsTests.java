package com.acme.dispatch.request;

import static com.acme.dispatch.support.DemoData.NORTH_INITIATOR_LOGIN;
import static com.acme.dispatch.support.DemoData.NORTH_OPERATOR_LOGIN;
import static com.acme.dispatch.support.TestClockConfiguration.START;
import static com.acme.dispatch.support.TestSecurity.asUser;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.acme.dispatch.auth.AppUserRepository;
import com.acme.dispatch.config.IdGenerator;
import com.acme.dispatch.problem.ProblemDao;
import com.acme.dispatch.problem.ProblemKind;
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

/** The registry's problem filter and the number of open problems per row (web-problems.md §3.2). */
@IntegrationTest
class RequestListProblemsTests {

    @Autowired
    MockMvc mvc;
    @Autowired
    AppUserRepository users;
    @Autowired
    TestRequests requests;
    @Autowired
    ProblemDao problems;
    @Autowired
    IdGenerator ids;
    @Autowired
    MutableClock clock;
    @Autowired
    JdbcClient jdbc;

    private RequestCard none;
    private RequestCard one;
    private RequestCard two;
    private RequestCard closed;

    @BeforeEach
    void setUp() {
        clock.set(START);
        // A second apart, so "oldest first" is the order of creation.
        none = requests.create("1");
        clock.advance(java.time.Duration.ofSeconds(1));
        one = requests.create("2");
        clock.advance(java.time.Duration.ofSeconds(1));
        two = requests.create("3");
        clock.advance(java.time.Duration.ofSeconds(1));
        closed = requests.create("4");
        open(ProblemKind.DELIVERY_UNKNOWN, one);
        open(ProblemKind.DELIVERY_UNKNOWN, two);
        open(ProblemKind.DELIVERY_FAILED, two);
        resolveBySystem(open(ProblemKind.DELIVERY_UNKNOWN, closed));
    }

    @AfterEach
    void cleanUp() {
        TestRows.deleteRequestData(jdbc);
        clock.set(START);
    }

    @Test
    void operatorFiltersRequestsWithOpenProblems() throws Exception {
        mvc.perform(get("/api/requests").param("hasOpenProblems", "true").with(asUser(users, NORTH_OPERATOR_LOGIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", containsInAnyOrder(one.id().toString(), two.id().toString())))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void operatorFiltersRequestsWithoutOpenProblems() throws Exception {
        mvc.perform(get("/api/requests").param("hasOpenProblems", "false").with(asUser(users, NORTH_OPERATOR_LOGIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id",
                        containsInAnyOrder(none.id().toString(), closed.id().toString())))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void problemFilterCombinesWithOtherFilters() throws Exception {
        mvc.perform(get("/api/requests").param("hasOpenProblems", "true").param("deliveryStatus", "PENDING")
                        .param("businessStatus", "AWAITING").with(asUser(users, NORTH_OPERATOR_LOGIN)))
                .andExpect(jsonPath("$.items[*].id", containsInAnyOrder(one.id().toString(), two.id().toString())));
        mvc.perform(get("/api/requests").param("hasOpenProblems", "true").param("deliveryStatus", "FAILED")
                        .with(asUser(users, NORTH_OPERATOR_LOGIN)))
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void operatorSeesHowManyProblemsAreOpen() throws Exception {
        mvc.perform(get("/api/requests").param("sort", "createdAt,asc").with(asUser(users, NORTH_OPERATOR_LOGIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", contains(none.id().toString(), one.id().toString(),
                        two.id().toString(), closed.id().toString())))
                .andExpect(jsonPath("$.items[*].openProblems", contains(0, 1, 2, 0)));
    }

    @Test
    void initiatorSeesNoProblemCount() throws Exception {
        mvc.perform(get("/api/requests").with(asUser(users, NORTH_INITIATOR_LOGIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(4)))
                .andExpect(jsonPath("$.items[*].openProblems", everyItem(nullValue())));
    }

    @Test
    void initiatorCannotFilterByProblems() throws Exception {
        for (String value : new String[] {"true", "false"}) {
            mvc.perform(get("/api/requests").param("hasOpenProblems", value)
                            .with(asUser(users, NORTH_INITIATOR_LOGIN)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    @Test
    void wrongProblemFilterIsRejected() throws Exception {
        mvc.perform(get("/api/requests").param("hasOpenProblems", "yes").with(asUser(users, NORTH_OPERATOR_LOGIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private UUID open(ProblemKind kind, RequestCard request) {
        UUID id = ids.newId();
        problems.open(id, kind, request.id(), null, "why", START);
        return id;
    }

    private void resolveBySystem(UUID id) {
        jdbc.sql("UPDATE problem SET status = 'RESOLVED', resolved_at = opened_at, decision = 'SYSTEM' WHERE id = :id")
                .param("id", id)
                .update();
    }
}
