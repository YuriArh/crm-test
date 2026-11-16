package com.acme.dispatch.problem;

import static com.acme.dispatch.support.DemoData.NORTH_INITIATOR_LOGIN;
import static com.acme.dispatch.support.DemoData.NORTH_OPERATOR_LOGIN;
import static com.acme.dispatch.support.DemoData.SOUTH;
import static com.acme.dispatch.support.DemoData.SOUTH_INITIATOR_LOGIN;
import static com.acme.dispatch.support.TestClockConfiguration.START;
import static com.acme.dispatch.support.TestFeed.succeeded;
import static com.acme.dispatch.support.TestSecurity.asUser;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.acme.dispatch.auth.AppUserRepository;
import com.acme.dispatch.config.IdGenerator;
import com.acme.dispatch.request.RequestCard;
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

/** Who may see problems and act on them (NF02, operator-problems.md §2.4–2.5). */
@IntegrationTest
class ProblemAccessTests {

    /** No demo operator of the south service exists; this one lives only for these tests. */
    static final UUID SOUTH_OPERATOR = UUID.fromString("0195f000-0000-7000-8000-000000000202");
    static final String SOUTH_OPERATOR_LOGIN = "south.operator.test";

    @Autowired
    MockMvc mvc;
    @Autowired
    AppUserRepository users;
    @Autowired
    TestRequests requests;
    @Autowired
    TestResults results;
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
        jdbc.sql("""
                        INSERT INTO app_user (id, username, display_name, password_hash, role, service_id, created_at)
                        VALUES (:id, :login, 'Оператор (Юг)', 'x', 'OPERATOR', :service, now())
                        ON CONFLICT DO NOTHING""")
                .param("id", SOUTH_OPERATOR)
                .param("login", SOUTH_OPERATOR_LOGIN)
                .param("service", SOUTH)
                .update();
    }

    @AfterEach
    void cleanUp() {
        TestRows.deleteRequestData(jdbc);
        jdbc.sql("DELETE FROM app_user WHERE id = :id").param("id", SOUTH_OPERATOR).update();
    }

    @Test
    void initiatorCannotListProblems() throws Exception {
        mvc.perform(get("/api/problems").with(asUser(users, NORTH_INITIATOR_LOGIN)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void operatorSeesOwnServiceAndRecordsWithoutRequest() throws Exception {
        UUID north = open(requests.create("1"));
        UUID south = open(requests.createAs(SOUTH_INITIATOR_LOGIN, "Pallets", "1", "Main st 1"));
        results.deliver(succeeded(1, UUID.randomUUID(), "TRK-00000001", "325.00"));
        String orphan = jdbc.sql("SELECT id::text FROM problem WHERE request_id IS NULL").query(String.class).single();

        mvc.perform(get("/api/problems").with(asUser(users, NORTH_OPERATOR_LOGIN)))
                .andExpect(jsonPath("$.items[*].id", containsInAnyOrder(north.toString(), orphan)));
        mvc.perform(get("/api/problems").with(asUser(users, SOUTH_OPERATOR_LOGIN)))
                .andExpect(jsonPath("$.items[*].id", containsInAnyOrder(south.toString(), orphan)));
    }

    @Test
    void initiatorCardHasNoProblems() throws Exception {
        RequestCard request = requests.create("1");
        open(request);

        mvc.perform(get("/api/requests/{id}", request.id()).with(asUser(users, NORTH_INITIATOR_LOGIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.problems").doesNotExist())
                .andExpect(jsonPath("$.actions", hasSize(0)));
    }

    private UUID open(RequestCard request) {
        UUID id = ids.newId();
        problems.open(id, ProblemKind.DELIVERY_UNKNOWN, request.id(), null, "why", START);
        return id;
    }
}
