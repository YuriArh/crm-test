package com.acme.dispatch.request;

import static com.acme.dispatch.support.DemoData.NORTH;
import static com.acme.dispatch.support.DemoData.NORTH_INITIATOR;
import static com.acme.dispatch.support.DemoData.NORTH_INITIATOR_LOGIN;
import static com.acme.dispatch.support.DemoData.NORTH_OPERATOR_LOGIN;
import static com.acme.dispatch.support.DemoData.SOUTH_INITIATOR_LOGIN;
import static com.acme.dispatch.support.TestSecurity.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.acme.dispatch.auth.AppUserRepository;
import com.acme.dispatch.exchange.PayloadHasher;
import com.acme.dispatch.support.IntegrationTest;
import com.acme.dispatch.support.MutableClock;
import com.acme.dispatch.support.TestClockConfiguration;
import com.acme.dispatch.support.TestRows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class RequestCreateTests {

    static final String VALID = body("Pallets", "12.5", "Main st 1", "2026-11-20");

    @Autowired
    MockMvc mvc;
    @Autowired
    AppUserRepository users;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    MutableClock clock;
    @Autowired
    JsonMapper json;

    @BeforeEach
    void resetClock() {
        clock.set(TestClockConfiguration.START);
    }

    @AfterEach
    void cleanUp() {
        TestRows.deleteRequestData(jdbc);
    }

    @Test
    void createsTheRequestAndAPendingOperationInOneGo() throws Exception {
        MvcResult result = create(NORTH_INITIATOR_LOGIN, key(), VALID)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern("/api/requests/[0-9a-f-]{36}")))
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.number").value(matchesPattern("DR-\\d{6}")))
                .andExpect(jsonPath("$.businessStatus").value("AWAITING"))
                .andExpect(jsonPath("$.deliveryStatus").value("PENDING"))
                .andExpect(jsonPath("$.owner.id").value(NORTH_INITIATOR.toString()))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.overdue").value(false))
                .andExpect(jsonPath("$.exchange").doesNotExist())
                .andReturn();

        JsonNode card = json.readTree(result.getResponse().getContentAsString());
        UUID id = UUID.fromString(card.get("id").asString());
        assertThat(jdbc.sql("SELECT service_id FROM delivery_request WHERE id = :id").param("id", id)
                .query(UUID.class).single()).isEqualTo(NORTH);
        assertThat(jdbc.sql("SELECT operation_id FROM dispatch_operation WHERE request_id = :id AND delivery_status = 'PENDING'")
                .param("id", id).query(UUID.class).single()).hasToString(card.get("operationId").asString());
        assertThat(jdbc.sql("SELECT payload_hash FROM dispatch_operation WHERE request_id = :id").param("id", id)
                .query(String.class).single())
                .isEqualTo(PayloadHasher.hash(new DeliveryContent("Pallets", new BigDecimal("12.5"), "Main st 1",
                        LocalDate.of(2026, 11, 20))));
    }

    @Test
    void invalidFieldsAreReportedOneByOne() throws Exception {
        create(NORTH_INITIATOR_LOGIN, key(), body("", "0", "Main st 1", "2026-11-20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field").value(hasItems("cargoDescription", "weightKg")));

        assertThat(countRequests()).isZero();
    }

    @Test
    void deadlineBeforeTodayIsRejectedAndTheKeyIsNotConsumed() throws Exception {
        String key = key();
        create(NORTH_INITIATOR_LOGIN, key, body("Pallets", "1", "Main st 1", "2026-11-11"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("deadline"));

        assertThat(countRequests()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM idempotency_key").query(Long.class).single()).isZero();
    }

    @Test
    void todayIsTakenInMoscow() throws Exception {
        create(NORTH_INITIATOR_LOGIN, key(), body("Pallets", "1", "Main st 1", "2026-11-12"))
                .andExpect(status().isCreated());

        // 21:30 UTC is already 13 November in Moscow, so a deadline of the 12th is in the past.
        clock.set(Instant.parse("2026-11-12T21:30:00Z"));
        create(NORTH_INITIATOR_LOGIN, key(), body("Pallets", "1", "Main st 1", "2026-11-12"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void operatorCannotCreate() throws Exception {
        create(NORTH_OPERATOR_LOGIN, key(), VALID)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void idempotencyKeyIsRequired() throws Exception {
        mvc.perform(post("/api/requests").with(asUser(users, NORTH_INITIATOR_LOGIN)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        create(NORTH_INITIATOR_LOGIN, "   ", VALID)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        assertThat(countRequests()).isZero();
    }

    @Test
    void repeatWithTheSameKeyReturnsTheSameRequest() throws Exception {
        String key = key();
        String firstId = idOf(create(NORTH_INITIATOR_LOGIN, key, VALID).andExpect(status().isCreated()).andReturn());

        MvcResult repeat = create(NORTH_INITIATOR_LOGIN, key, VALID)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn();

        assertThat(idOf(repeat)).isEqualTo(firstId);
        assertThat(countRequests()).isEqualTo(1);
    }

    @Test
    void weightScaleDoesNotMakeItADifferentRequest() throws Exception {
        String key = key();
        String firstId = idOf(create(NORTH_INITIATOR_LOGIN, key, body("Pallets", "12.5", "Main st 1", "2026-11-20"))
                .andReturn());

        String secondId = idOf(create(NORTH_INITIATOR_LOGIN, key, body("Pallets", "12.50", "Main st 1", "2026-11-20"))
                .andExpect(status().isCreated()).andReturn());

        assertThat(secondId).isEqualTo(firstId);
    }

    @Test
    void sameKeyWithADifferentBodyIsRejected() throws Exception {
        String key = key();
        create(NORTH_INITIATOR_LOGIN, key, VALID).andExpect(status().isCreated());

        create(NORTH_INITIATOR_LOGIN, key, body("Boxes", "3", "Main st 1", "2026-11-20"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(countRequests()).isEqualTo(1);
    }

    @Test
    void keysOfDifferentUsersAreIndependent() throws Exception {
        String key = key();
        create(NORTH_INITIATOR_LOGIN, key, VALID).andExpect(status().isCreated());
        create(SOUTH_INITIATOR_LOGIN, key, VALID).andExpect(status().isCreated());

        assertThat(countRequests()).isEqualTo(2);
    }

    @Test
    void parallelDuplicatesCreateOneRequest() throws Exception {
        String key = key();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<CompletableFuture<MvcResult>> calls = List.of(1, 2).stream()
                    .map(i -> CompletableFuture.supplyAsync(() -> {
                        try {
                            start.await();
                            return create(NORTH_INITIATOR_LOGIN, key, VALID).andReturn();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }, pool))
                    .toList();
            start.countDown();

            List<MvcResult> results = calls.stream().map(CompletableFuture::join).toList();
            assertThat(results).allSatisfy(result -> assertThat(result.getResponse().getStatus()).isEqualTo(201));
            assertThat(idOf(results.get(0))).isEqualTo(idOf(results.get(1)));
        }
        assertThat(countRequests()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM dispatch_operation").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void anonymousCannotCreate() throws Exception {
        mvc.perform(post("/api/requests").with(csrf()).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions create(String login, String key, String body) throws Exception {
        return mvc.perform(post("/api/requests")
                .with(asUser(users, login))
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String idOf(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).get("id").asString();
    }

    private long countRequests() {
        return jdbc.sql("SELECT count(*) FROM delivery_request").query(Long.class).single();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    static String body(String cargo, String weight, String address, String deadline) {
        return """
                {"cargoDescription":"%s","weightKg":%s,"deliveryAddress":"%s","deadline":"%s"}"""
                .formatted(cargo, weight, address, deadline);
    }
}
