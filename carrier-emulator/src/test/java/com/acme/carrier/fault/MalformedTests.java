package com.acme.carrier.fault;

import static com.acme.carrier.support.Contract.matchesContract;
import static com.acme.carrier.support.Contract.violations;
import static com.acme.carrier.support.Operations.body;
import static com.acme.carrier.support.Operations.submit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.acme.carrier.execution.Executor;
import com.acme.carrier.support.FakeSleeper;
import com.acme.carrier.support.Feed;
import com.acme.carrier.support.IntegrationTest;
import com.acme.carrier.support.MutableClock;
import com.acme.carrier.support.TestRows;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Fault mode {@code malformed} ({@code docs/specs/carrier-faults.md} §3.5): while it is on, {@code GET /results}
 * answers with a broken price in every {@code SUCCEEDED} entry. Only the response is spoiled; nothing is stored.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class MalformedTests {

    static final Faults MALFORMED = new Faults(false, false, false, false, true);
    static final String BROKEN_PRICE = "10000000000000.00";
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    Executor executor;
    @Autowired
    FaultModes faults;
    @Autowired
    MutableClock clock;
    @Autowired
    FakeSleeper sleeper;

    private long start;

    @BeforeEach
    void setUp() {
        start = Feed.lastSeq(jdbc);
    }

    @AfterEach
    void cleanUp() {
        TestRows.resetState(faults, clock, sleeper);
        TestRows.deleteAll(jdbc);
    }

    @Test
    void priceOfSucceededEntriesIsBrokenInTheResponseOnly() throws Exception {
        UUID id = submitAndExecute(body(id()));
        faults.set(MALFORMED);

        List<JsonNode> items = rawPage();
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(items.get(0).get("price").isNull()).isTrue();
        assertThat(items.get(1).get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(items.get(1).get("price").decimalValue()).isEqualByComparingTo(BROKEN_PRICE);

        BigDecimal stored = jdbc.sql("SELECT price FROM feed_entry WHERE operation_id = :id AND status = 'SUCCEEDED'")
                .param("id", id).query(BigDecimal.class).single();
        assertThat(stored).isEqualByComparingTo("612.50");
        mockMvc.perform(get("/operations/{id}", id))
                .andExpect(status().isOk())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.result.price").value(612.5));
        mockMvc.perform(get("/admin/operations").param("operationId", id.toString()))
                .andExpect(status().isOk())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.items[0].result.price").value(612.5))
                .andExpect(jsonPath("$.items[0].faults", Matchers.empty()));
    }

    @Test
    void theResponseBreaksTheContractExactlyInThePrice() throws Exception {
        submitAndExecute(body(id()));
        faults.set(MALFORMED);

        List<String> violations = violations("/results", rawBody());

        assertThat(violations).isNotEmpty().allSatisfy(violation -> assertThat(violation).startsWith("/items/1/price"));
    }

    @Test
    void whenTheModeIsOffTheSameEntriesFollowTheContract() throws Exception {
        submitAndExecute(body(id()));
        faults.set(MALFORMED);
        faults.set(Faults.OFF);

        assertThat(violations("/results", rawBody())).isEmpty();
        assertThat(rawPage().get(1).get("price").decimalValue()).isEqualByComparingTo("612.50");
    }

    @Test
    void rejectionsAreNotChanged() throws Exception {
        Map<String, Object> heavy = new java.util.HashMap<>(body(id()));
        heavy.put("weightKg", 15000);
        submitAndExecute(heavy);
        faults.set(MALFORMED);

        List<JsonNode> items = rawPage();
        assertThat(items.get(1).get("status").asString()).isEqualTo("REJECTED");
        assertThat(items.get(1).get("price").isNull()).isTrue();
        assertThat(violations("/results", rawBody())).isEmpty();
    }

    @Test
    void bothCopiesOfADuplicatedEntryAreBroken() throws Exception {
        faults.set(new Faults(false, false, true, false, false));
        submitAndExecute(body(id()));
        faults.set(new Faults(false, false, false, false, true));

        List<JsonNode> succeeded = rawPage().stream()
                .filter(item -> item.get("status").asString().equals("SUCCEEDED")).toList();
        assertThat(succeeded).hasSize(2)
                .allSatisfy(item -> assertThat(item.get("price").decimalValue()).isEqualByComparingTo(BROKEN_PRICE));
    }

    @Test
    void unavailableWins() throws Exception {
        faults.set(new Faults(true, false, false, false, true));

        mockMvc.perform(get("/results").param("after", String.valueOf(start)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(matchesContract());
    }

    @Test
    void everyBrokenEntryIsLogged(CapturedOutput output) throws Exception {
        UUID id = submitAndExecute(body(id()));
        faults.set(MALFORMED);

        rawPage();

        assertThat(output.getOut()).contains("Fault malformed").contains(id.toString());
    }

    @Test
    void theSwitchIsSetAndReadBackAndRequiredInAPut() throws Exception {
        mockMvc.perform(put("/admin/faults").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unavailable\":false,\"loseResponse\":false,\"duplicate\":false,\"delayed\":false,"
                                + "\"malformed\":true}"))
                .andExpect(status().isOk())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.malformed").value(true));
        mockMvc.perform(get("/admin/faults"))
                .andExpect(status().isOk())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.malformed").value(true));

        mockMvc.perform(put("/admin/faults").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unavailable\":false,\"loseResponse\":false,\"duplicate\":false,\"delayed\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private static UUID id() {
        return UUID.randomUUID();
    }

    private UUID submitAndExecute(Map<String, Object> body) throws Exception {
        mockMvc.perform(submit(body)).andExpect(status().isAccepted()).andExpect(matchesContract());
        executor.runOnce();
        return UUID.fromString(body.get("operationId").toString());
    }

    /** The feed after this test's start, read without the contract check (the mode breaks it on purpose). */
    private String rawBody() throws Exception {
        return mockMvc.perform(get("/results").param("after", String.valueOf(start)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private List<JsonNode> rawPage() throws Exception {
        List<JsonNode> items = new ArrayList<>();
        JSON.readTree(rawBody()).get("items").forEach(items::add);
        return items;
    }
}
