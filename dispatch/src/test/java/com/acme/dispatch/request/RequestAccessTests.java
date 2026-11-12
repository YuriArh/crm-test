package com.acme.dispatch.request;

import static com.acme.dispatch.common.SqlTime.toSql;
import static com.acme.dispatch.support.DemoData.NORTH;
import static com.acme.dispatch.support.DemoData.NORTH_INITIATOR;
import static com.acme.dispatch.support.DemoData.NORTH_INITIATOR_LOGIN;
import static com.acme.dispatch.support.DemoData.NORTH_OPERATOR;
import static com.acme.dispatch.support.DemoData.NORTH_OPERATOR_LOGIN;
import static com.acme.dispatch.support.DemoData.SOUTH;
import static com.acme.dispatch.support.DemoData.SOUTH_INITIATOR;
import static com.acme.dispatch.support.DemoData.SOUTH_INITIATOR_LOGIN;
import static com.acme.dispatch.support.TestSecurity.asUser;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.acme.dispatch.auth.AppUserRepository;
import com.acme.dispatch.config.IdGenerator;
import com.acme.dispatch.exchange.DispatchOperationDao;
import com.acme.dispatch.support.IntegrationTest;
import com.acme.dispatch.support.MutableClock;
import com.acme.dispatch.support.TestClockConfiguration;
import com.acme.dispatch.support.TestRows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** Registry and card: access scope, filters, paging, sorting and the operator-only exchange block. */
@IntegrationTest
class RequestAccessTests {

    static final Instant NOW = TestClockConfiguration.START;

    @Autowired
    MockMvc mvc;
    @Autowired
    AppUserRepository users;
    @Autowired
    DeliveryRequestRepository requests;
    @Autowired
    DispatchOperationDao operations;
    @Autowired
    IdGenerator ids;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    MutableClock clock;

    /** north.initiator, deadline 2026-11-20, awaiting. */
    DeliveryRequest future;
    /** north.initiator, deadline 2026-11-10 (already past), awaiting: overdue. */
    DeliveryRequest past;
    /** north.initiator, deadline 2026-11-15, succeeded, acknowledged, assigned to north.operator. */
    DeliveryRequest done;
    /** south.initiator, deadline 2026-11-18. */
    DeliveryRequest south;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        TestRows.deleteRequestData(jdbc);
        future = create(NORTH, NORTH_INITIATOR, "Pallets", LocalDate.of(2026, 11, 20), NOW);
        past = create(NORTH, NORTH_INITIATOR, "Boxes", LocalDate.of(2026, 11, 10), NOW.plusSeconds(60));
        done = create(NORTH, NORTH_INITIATOR, "Crates", LocalDate.of(2026, 11, 15), NOW.plusSeconds(120));
        south = create(SOUTH, SOUTH_INITIATOR, "Barrels", LocalDate.of(2026, 11, 18), NOW.plusSeconds(180));

        jdbc.sql("""
                        UPDATE delivery_request
                        SET business_status = 'SUCCEEDED', tracking_number = 'TRK-1', price = 1500.00,
                            result_at = :now, assignee_id = :assignee, version = version + 1, updated_at = :now
                        WHERE id = :id""")
                .param("now", toSql(NOW.plusSeconds(600)))
                .param("assignee", NORTH_OPERATOR)
                .param("id", done.getId())
                .update();
        jdbc.sql("""
                        UPDATE dispatch_operation
                        SET delivery_status = 'ACKNOWLEDGED', attempts = 1,
                            acknowledged_at = :now, acknowledged_via = 'RESPONSE'
                        WHERE request_id = :id""")
                .param("now", toSql(NOW.plusSeconds(300)))
                .param("id", done.getId())
                .update();
    }

    @AfterEach
    void tearDown() {
        TestRows.deleteRequestData(jdbc);
        clock.set(NOW);
    }

    @Test
    void initiatorSeesOnlyOwnRequests() throws Exception {
        mvc.perform(get("/api/requests").with(asUser(users, NORTH_INITIATOR_LOGIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                // default order: newest first
                .andExpect(jsonPath("$.items[*].id").value(contains(ids(done, past, future))));

        mvc.perform(get("/api/requests").with(asUser(users, SOUTH_INITIATOR_LOGIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(south.getId().toString()));
    }

    @Test
    void initiatorGetsNotFoundForSomeoneElsesRequest() throws Exception {
        mvc.perform(get("/api/requests/{id}", south.getId()).with(asUser(users, NORTH_INITIATOR_LOGIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/api/requests/{id}", future.getId()).with(asUser(users, SOUTH_INITIATOR_LOGIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        // A nonexistent or malformed id looks exactly the same.
        mvc.perform(get("/api/requests/{id}", UUID.randomUUID()).with(asUser(users, NORTH_INITIATOR_LOGIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/api/requests/not-a-uuid").with(asUser(users, NORTH_INITIATOR_LOGIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void operatorSeesOwnServiceOnly() throws Exception {
        mvc.perform(get("/api/requests").with(asUser(users, NORTH_OPERATOR_LOGIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.items[*].id").value(containsInAnyOrder(ids(future, past, done))));

        mvc.perform(get("/api/requests/{id}", south.getId()).with(asUser(users, NORTH_OPERATOR_LOGIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void filtersByBusinessAndDeliveryStatus() throws Exception {
        mvc.perform(get("/api/requests").param("businessStatus", "SUCCEEDED")
                        .with(asUser(users, NORTH_OPERATOR_LOGIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id").value(contains(ids(done))));
        mvc.perform(get("/api/requests").param("businessStatus", "AWAITING")
                        .with(asUser(users, NORTH_INITIATOR_LOGIN)))
                .andExpect(jsonPath("$.items[*].id").value(containsInAnyOrder(ids(future, past))));
        mvc.perform(get("/api/requests").param("deliveryStatus", "ACKNOWLEDGED")
                        .with(asUser(users, NORTH_INITIATOR_LOGIN)))
                .andExpect(jsonPath("$.items[*].id").value(contains(ids(done))))
                .andExpect(jsonPath("$.items[0].deliveryStatus").value("ACKNOWLEDGED"));
        mvc.perform(get("/api/requests").param("deliveryStatus", "PENDING").param("businessStatus", "AWAITING")
                        .with(asUser(users, NORTH_OPERATOR_LOGIN)))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/requests").param("businessStatus", "DONE")
                        .with(asUser(users, NORTH_OPERATOR_LOGIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void filtersByOverdueAndFollowsTheClock() throws Exception {
        mvc.perform(get("/api/requests").param("overdue", "true").with(asUser(users, NORTH_INITIATOR_LOGIN)))
                .andExpect(jsonPath("$.items[*].id").value(contains(ids(past))))
                .andExpect(jsonPath("$.items[0].overdue").value(true));
        mvc.perform(get("/api/requests").param("overdue", "false").with(asUser(users, NORTH_INITIATOR_LOGIN)))
                .andExpect(jsonPath("$.items[*].id").value(containsInAnyOrder(ids(future, done))))
                .andExpect(jsonPath("$.items[*].overdue").value(contains(false, false)));

        // 23:59 on 2026-11-20 in Moscow: the deadline day is still running.
        clock.set(Instant.parse("2026-11-20T20:59:00Z"));
        mvc.perform(get("/api/requests").param("overdue", "true").with(asUser(users, NORTH_INITIATOR_LOGIN)))
                .andExpect(jsonPath("$.items[*].id").value(contains(ids(past))));

        // 00:00 on 2026-11-21 in Moscow: overdue; the succeeded request never is.
        clock.set(Instant.parse("2026-11-20T21:00:00Z"));
        mvc.perform(get("/api/requests").param("overdue", "true").with(asUser(users, NORTH_INITIATOR_LOGIN)))
                .andExpect(jsonPath("$.items[*].id").value(containsInAnyOrder(ids(future, past))));
        mvc.perform(get("/api/requests/{id}", future.getId()).with(asUser(users, NORTH_INITIATOR_LOGIN)))
                .andExpect(jsonPath("$.overdue").value(true));
        mvc.perform(get("/api/requests/{id}", done.getId()).with(asUser(users, NORTH_INITIATOR_LOGIN)))
                .andExpect(jsonPath("$.overdue").value(false));
    }

    @Test
    void pagesAndSortsByDeadline() throws Exception {
        mvc.perform(get("/api/requests").param("size", "2").param("sort", "deadline,asc")
                        .with(asUser(users, NORTH_OPERATOR_LOGIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items[*].id").value(contains(ids(past, done))));

        mvc.perform(get("/api/requests").param("page", "1").param("size", "2").param("sort", "deadline,asc")
                        .with(asUser(users, NORTH_OPERATOR_LOGIN)))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.items[*].id").value(contains(ids(future))));

        mvc.perform(get("/api/requests").param("page", "5").with(asUser(users, NORTH_OPERATOR_LOGIN)))
                .andExpect(jsonPath("$.items").value(hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void invalidParametersAreRejected() throws Exception {
        mvc.perform(get("/api/requests").param("size", "101").with(asUser(users, NORTH_OPERATOR_LOGIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/api/requests").param("sort", "owner,asc").with(asUser(users, NORTH_OPERATOR_LOGIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void listItemHasTheRegistryFields() throws Exception {
        mvc.perform(get("/api/requests").param("businessStatus", "SUCCEEDED")
                        .with(asUser(users, NORTH_INITIATOR_LOGIN)))
                .andExpect(jsonPath("$.items[0].number").value(RequestNumber.format(done.getNumber())))
                .andExpect(jsonPath("$.items[0].cargoDescription").value("Crates"))
                .andExpect(jsonPath("$.items[0].deadline").value("2026-11-15"))
                .andExpect(jsonPath("$.items[0].businessStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.items[0].owner.id").value(NORTH_INITIATOR.toString()))
                .andExpect(jsonPath("$.items[0].owner.displayName").value("Инициатор (Север)"))
                .andExpect(jsonPath("$.items[0].assignee.id").value(NORTH_OPERATOR.toString()))
                .andExpect(jsonPath("$.items[0].assignee.displayName").value("Оператор (Север)"))
                .andExpect(jsonPath("$.items[0].createdAt").value("2026-11-12T09:02:00Z"));
    }

    @Test
    void operatorCardHasExchangeBlock() throws Exception {
        mvc.perform(get("/api/requests/{id}", done.getId()).with(asUser(users, NORTH_OPERATOR_LOGIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(done.getId().toString()))
                .andExpect(jsonPath("$.number").value("DR-%06d".formatted(done.getNumber())))
                .andExpect(jsonPath("$.weightKg").value(12.5))
                .andExpect(jsonPath("$.deliveryAddress").value("Main st 1"))
                .andExpect(jsonPath("$.businessStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.result.trackingNumber").value("TRK-1"))
                .andExpect(jsonPath("$.result.price").value(1500.00))
                .andExpect(jsonPath("$.result.resultAt").value("2026-11-12T09:10:00Z"))
                .andExpect(jsonPath("$.deliveryStatus").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.operationId").isNotEmpty())
                .andExpect(jsonPath("$.assignee.id").value(NORTH_OPERATOR.toString()))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.exchange.attempts").value(1))
                .andExpect(jsonPath("$.exchange.acknowledgedVia").value("RESPONSE"))
                .andExpect(jsonPath("$.exchange.acknowledgedAt").value("2026-11-12T09:05:00Z"));
    }

    @Test
    void initiatorCardHasNoExchangeBlock() throws Exception {
        mvc.perform(get("/api/requests/{id}", done.getId()).with(asUser(users, NORTH_INITIATOR_LOGIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(RequestNumber.format(done.getNumber())))
                .andExpect(jsonPath("$.result.trackingNumber").value("TRK-1"))
                .andExpect(jsonPath("$.exchange").doesNotExist());

        mvc.perform(get("/api/requests/{id}", future.getId()).with(asUser(users, NORTH_INITIATOR_LOGIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessStatus").value("AWAITING"))
                .andExpect(jsonPath("$.deliveryStatus").value("PENDING"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.overdue").value(false))
                .andExpect(jsonPath("$.exchange").doesNotExist());
    }

    @Test
    void unauthenticatedIsRejected() throws Exception {
        mvc.perform(get("/api/requests"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/requests/{id}", future.getId()))
                .andExpect(status().isUnauthorized());
    }

    private DeliveryRequest create(UUID serviceId, UUID ownerId, String cargo, LocalDate deadline, Instant at) {
        DeliveryContent content = new DeliveryContent(cargo, new BigDecimal("12.5"), "Main st 1", deadline);
        DeliveryRequest saved = requests.save(
                DeliveryRequest.create(ids.newId(), requests.nextNumber(), serviceId, ownerId, content, at));
        operations.insertPending(saved.getId(), ids.newId(), "a".repeat(64), at);
        return saved;
    }

    private static String[] ids(DeliveryRequest... list) {
        String[] result = new String[list.length];
        for (int i = 0; i < list.length; i++) {
            result[i] = list[i].getId().toString();
        }
        return result;
    }
}
