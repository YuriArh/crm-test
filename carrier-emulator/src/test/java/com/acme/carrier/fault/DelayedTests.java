package com.acme.carrier.fault;

import static com.acme.carrier.support.Contract.matchesContract;
import static com.acme.carrier.support.Operations.body;
import static com.acme.carrier.support.Operations.submit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.acme.carrier.config.CarrierProperties;
import com.acme.carrier.execution.Executor;
import com.acme.carrier.support.FakeSleeper;
import com.acme.carrier.support.Feed;
import com.acme.carrier.support.IntegrationTest;
import com.acme.carrier.support.MutableClock;
import com.acme.carrier.support.TestClockConfiguration;
import com.acme.carrier.support.TestRows;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** Fault mode {@code delayed} ({@code docs/specs/carrier-faults.md} §3.4). */
@IntegrationTest
class DelayedTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Faults DELAYED = new Faults(false, false, false, true);

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    FaultModes faults;
    @Autowired
    MutableClock clock;
    @Autowired
    FakeSleeper sleeper;
    @Autowired
    Executor executor;
    @Autowired
    CarrierProperties properties;

    private long start;
    private Duration delay;

    @BeforeEach
    void setUp() {
        start = Feed.lastSeq(jdbc);
        delay = properties.faults().delayedResultDelay();
    }

    @AfterEach
    void cleanUp() {
        TestRows.resetState(faults, clock, sleeper);
        TestRows.deleteAll(jdbc);
    }

    @Test
    void delayedOperationIsExecutedButNotPublished() throws Exception {
        faults.set(DELAYED);
        UUID id = submitted();

        assertThat(executor.runOnce()).isEqualTo(1);

        assertThat(feed(id)).isEmpty();
        Instant executedAt = TestClockConfiguration.NOW;
        mockMvc.perform(get("/operations/{id}", id))
                .andExpect(status().isOk())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.state").value("EXECUTED"))
                .andExpect(jsonPath("$.executedAt").value(executedAt.toString()))
                .andExpect(jsonPath("$.result.sequence").value(2))
                .andExpect(jsonPath("$.result.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.result.messageId").isNotEmpty());
        mockMvc.perform(get("/admin/operations").param("operationId", id.toString()))
                .andExpect(status().isOk())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.items[0].state").value("EXECUTED"))
                .andExpect(jsonPath("$.items[0].executionCount").value(1))
                .andExpect(jsonPath("$.items[0].faults", Matchers.contains("DELAYED")))
                .andExpect(jsonPath("$.items[0].resultPublishAt").value(executedAt.plus(delay).toString()));
    }

    @Test
    void nothingIsPublishedBeforeTheDueTime() throws Exception {
        faults.set(DELAYED);
        UUID id = submitted();
        executor.runOnce();

        clock.advance(delay.minusSeconds(1));
        assertThat(executor.publishDue()).isZero();
        executor.runOnce();

        assertThat(feed(id)).isEmpty();
        assertThat(publishAt(id)).isEqualTo(TestClockConfiguration.NOW.plus(delay));
    }

    @Test
    void dueResultIsPublishedFinalFirstWithTheStoredMessageIds() throws Exception {
        faults.set(DELAYED);
        UUID id = submitted();
        executor.runOnce();
        JsonNode state = json(mockMvc.perform(get("/operations/{id}", id))
                .andExpect(status().isOk())
                .andExpect(matchesContract())
                .andReturn().getResponse().getContentAsString());
        String resultMessageId = state.get("result").get("messageId").asString();
        UUID inProgressMessageId = jdbc.sql("SELECT in_progress_message_id FROM operation WHERE operation_id = :id")
                .param("id", id).query(UUID.class).single();

        clock.advance(delay);
        assertThat(executor.publishDue()).isEqualTo(1);

        List<JsonNode> entries = feed(id);
        assertThat(entries).hasSize(2);
        JsonNode result = entries.get(0);
        JsonNode inProgress = entries.get(1);
        assertThat(result.get("sequence").asInt()).isEqualTo(2);
        assertThat(result.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(result.get("messageId").asString()).isEqualTo(resultMessageId);
        assertThat(result.get("trackingNumber").asString()).isEqualTo(state.get("result").get("trackingNumber").asString());
        assertThat(result.get("price").decimalValue()).isEqualByComparingTo(state.get("result").get("price").decimalValue());
        assertThat(inProgress.get("sequence").asInt()).isEqualTo(1);
        assertThat(inProgress.get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(inProgress.get("messageId").asString()).isEqualTo(inProgressMessageId.toString());
        assertThat(result.get("feedSeq").asLong()).isLessThan(inProgress.get("feedSeq").asLong());
        assertThat(result.get("occurredAt").asString()).isEqualTo(TestClockConfiguration.NOW.toString());
        assertThat(inProgress.get("occurredAt").asString()).isEqualTo(TestClockConfiguration.NOW.toString());

        mockMvc.perform(get("/admin/operations").param("operationId", id.toString()))
                .andExpect(status().isOk())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.items[0].faults", Matchers.contains("DELAYED")))
                .andExpect(jsonPath("$.items[0].resultPublishAt").value(Matchers.nullValue()));

        assertThat(executor.publishDue()).isZero();
        assertThat(feed(id)).hasSize(2);
    }

    @Test
    void rejectedResultIsPublishedWithItsReason() throws Exception {
        faults.set(DELAYED);
        var body = body(UUID.randomUUID());
        body.put("deliveryAddress", "Тест-отказ st 1");
        UUID id = UUID.fromString((String) body.get("operationId"));
        mockMvc.perform(submit(body)).andExpect(status().isAccepted()).andExpect(matchesContract());
        executor.runOnce();

        clock.advance(delay);
        executor.runOnce();

        List<JsonNode> entries = feed(id);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).get("status").asString()).isEqualTo("REJECTED");
        assertThat(entries.get(0).get("reason").asString()).isNotBlank();
        assertThat(entries.get(1).get("status").asString()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void modeSwitchedOffAfterReceiptStillDelays() throws Exception {
        faults.set(DELAYED);
        UUID id = submitted();
        faults.set(Faults.OFF);

        executor.runOnce();

        assertThat(feed(id)).isEmpty();
        assertThat(publishAt(id)).isNotNull();
    }

    @Test
    void modeSwitchedOnAfterReceiptDoesNotDelay() throws Exception {
        UUID id = submitted();
        faults.set(DELAYED);

        executor.runOnce();

        assertThat(feed(id)).extracting(e -> e.get("status").asString())
                .containsExactly("IN_PROGRESS", "SUCCEEDED");
        assertThat(publishAt(id)).isNull();
    }

    @Test
    void normalOperationInTheSamePassIsPublishedAtOnce() throws Exception {
        faults.set(DELAYED);
        UUID delayed = submitted();
        faults.set(Faults.OFF);
        UUID normal = submitted();

        assertThat(executor.runOnce()).isEqualTo(2);

        assertThat(feed(delayed)).isEmpty();
        assertThat(feed(normal)).extracting(e -> e.get("status").asString())
                .containsExactly("IN_PROGRESS", "SUCCEEDED");
        assertThat(publishAt(normal)).isNull();
    }

    @Test
    void pendingPublicationIsDatabaseStateAndSurvivesRestart() throws Exception {
        faults.set(DELAYED);
        UUID id = submitted();
        executor.runOnce();
        // A restart loses the in-memory switches; the due time stays in the operation row.
        faults.set(Faults.OFF);
        assertThat(publishAt(id)).isEqualTo(TestClockConfiguration.NOW.plus(delay));

        clock.advance(delay.plusSeconds(5));
        executor.runOnce();

        assertThat(feed(id)).extracting(e -> e.get("status").asString())
                .containsExactly("SUCCEEDED", "IN_PROGRESS");
        assertThat(publishAt(id)).isNull();
    }

    @Test
    void deferredPublicationIsDuplicatedTooWithDuplicate() throws Exception {
        faults.set(new Faults(false, false, true, true));
        UUID id = submitted();
        faults.set(Faults.OFF);
        executor.runOnce();
        assertThat(feed(id)).isEmpty();
        JsonNode state = json(mockMvc.perform(get("/operations/{id}", id))
                .andExpect(status().isOk())
                .andExpect(matchesContract())
                .andReturn().getResponse().getContentAsString());
        String resultMessageId = state.get("result").get("messageId").asString();
        UUID inProgressMessageId = jdbc.sql("SELECT in_progress_message_id FROM operation WHERE operation_id = :id")
                .param("id", id).query(UUID.class).single();
        mockMvc.perform(get("/admin/operations").param("operationId", id.toString()))
                .andExpect(status().isOk())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.items[0].faults", Matchers.contains("DUPLICATE", "DELAYED")));

        clock.advance(delay);
        assertThat(executor.publishDue()).isEqualTo(1);

        List<JsonNode> entries = feed(id);
        assertThat(entries).hasSize(4);
        DuplicateTests.assertDuplicatePair(entries.get(0), entries.get(1));
        DuplicateTests.assertDuplicatePair(entries.get(2), entries.get(3));
        assertThat(entries.get(0).get("sequence").asInt()).isEqualTo(2);
        assertThat(entries.get(0).get("messageId").asString()).isEqualTo(resultMessageId);
        assertThat(entries.get(2).get("sequence").asInt()).isEqualTo(1);
        assertThat(entries.get(2).get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(entries.get(2).get("messageId").asString()).isEqualTo(inProgressMessageId.toString());
        assertThat(publishAt(id)).isNull();
    }

    private UUID submitted() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(submit(body(id))).andExpect(status().isAccepted()).andExpect(matchesContract());
        return id;
    }

    /** Feed entries of one operation published since the test started, in feed order. */
    private List<JsonNode> feed(UUID operationId) throws Exception {
        return Feed.of(mockMvc, start, operationId);
    }

    private Instant publishAt(UUID operationId) {
        return jdbc.sql("SELECT result_publish_at FROM operation WHERE operation_id = :id")
                .param("id", operationId)
                .query((rs, row) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant())
                .list().getFirst();
    }

    private static JsonNode json(String content) {
        return JSON.readTree(content);
    }
}
