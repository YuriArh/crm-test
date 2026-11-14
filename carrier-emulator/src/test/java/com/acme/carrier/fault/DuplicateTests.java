package com.acme.carrier.fault;

import static com.acme.carrier.support.Contract.matchesContract;
import static com.acme.carrier.support.Operations.body;
import static com.acme.carrier.support.Operations.submit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.acme.carrier.execution.Executor;
import com.acme.carrier.support.FakeSleeper;
import com.acme.carrier.support.Feed;
import com.acme.carrier.support.IntegrationTest;
import com.acme.carrier.support.MutableClock;
import com.acme.carrier.support.TestRows;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** Fault mode {@code duplicate} ({@code docs/specs/carrier-faults.md} §3.3). */
@IntegrationTest
class DuplicateTests {

    static final Faults DUPLICATE = new Faults(false, false, true, false);

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
    void everyEntryIsPublishedTwiceWithTheSameMessageIdAndAdjacentFeedSeq() throws Exception {
        faults.set(DUPLICATE);
        UUID id = UUID.randomUUID();
        mockMvc.perform(submit(body(id))).andExpect(status().isAccepted()).andExpect(matchesContract());
        executor.runOnce();

        List<JsonNode> items = Feed.page(mockMvc, start, 100);
        assertThat(items).hasSize(4);
        assertDuplicatePair(items.get(0), items.get(1));
        assertDuplicatePair(items.get(2), items.get(3));
        assertThat(items.get(0).get("operationId").asString()).isEqualTo(id.toString());
        assertThat(items.get(0).get("sequence").asInt()).isEqualTo(1);
        assertThat(items.get(0).get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(items.get(2).get("sequence").asInt()).isEqualTo(2);
        assertThat(items.get(2).get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(items.get(0).get("messageId")).isNotEqualTo(items.get(2).get("messageId"));
    }

    @Test
    void modeSwitchedOffAfterReceiptStillActs() throws Exception {
        faults.set(DUPLICATE);
        mockMvc.perform(submit(body(UUID.randomUUID()))).andExpect(status().isAccepted());
        faults.set(Faults.OFF);
        executor.runOnce();

        List<JsonNode> items = Feed.page(mockMvc, start, 100);
        assertThat(items).hasSize(4);
        assertDuplicatePair(items.get(0), items.get(1));
        assertDuplicatePair(items.get(2), items.get(3));
    }

    @Test
    void modeSwitchedOnOnlyAfterReceiptDoesNotAct() throws Exception {
        mockMvc.perform(submit(body(UUID.randomUUID()))).andExpect(status().isAccepted());
        faults.set(DUPLICATE);
        executor.runOnce();

        List<JsonNode> items = Feed.page(mockMvc, start, 100);
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("messageId")).isNotEqualTo(items.get(1).get("messageId"));
    }

    @Test
    void operationStateShowsTheSameFinalMessageAsTheFeed() throws Exception {
        faults.set(DUPLICATE);
        UUID id = UUID.randomUUID();
        mockMvc.perform(submit(body(id))).andExpect(status().isAccepted());
        executor.runOnce();
        List<JsonNode> items = Feed.page(mockMvc, start, 100);
        String finalMessageId = items.get(2).get("messageId").asString();

        mockMvc.perform(get("/operations/{id}", id))
                .andExpect(status().isOk())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.state").value("EXECUTED"))
                .andExpect(jsonPath("$.result.messageId").value(finalMessageId))
                .andExpect(jsonPath("$.result.sequence").value(2))
                .andExpect(jsonPath("$.result.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.result.trackingNumber").value(items.get(2).get("trackingNumber").asString()));
    }

    @Test
    void adminShowsTheRememberedMode() throws Exception {
        faults.set(DUPLICATE);
        UUID id = UUID.randomUUID();
        mockMvc.perform(submit(body(id))).andExpect(status().isAccepted());
        executor.runOnce();

        mockMvc.perform(get("/admin/operations").param("operationId", id.toString()))
                .andExpect(status().isOk())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.items[0].faults", Matchers.contains("DUPLICATE")))
                .andExpect(jsonPath("$.items[0].state").value("EXECUTED"))
                .andExpect(jsonPath("$.items[0].executionCount").value(1));
    }

    @Test
    void pagingAcrossDuplicatedEntriesSkipsNothing() throws Exception {
        faults.set(DUPLICATE);
        mockMvc.perform(submit(body(UUID.randomUUID()))).andExpect(status().isAccepted());
        mockMvc.perform(submit(body(UUID.randomUUID()))).andExpect(status().isAccepted());
        executor.runOnce();
        List<JsonNode> all = Feed.page(mockMvc, start, 100);
        assertThat(all).hasSize(8);

        List<JsonNode> paged = new ArrayList<>();
        long cursor = start;
        for (int page = 0; page < 10; page++) {
            List<JsonNode> items = Feed.page(mockMvc, cursor, 3);
            if (items.isEmpty()) {
                break;
            }
            paged.addAll(items);
            cursor = items.getLast().get("feedSeq").asLong();
        }

        assertThat(paged).isEqualTo(all);
    }

    /** Same message delivered twice: identical apart from {@code feedSeq}, which is adjacent. */
    static void assertDuplicatePair(JsonNode first, JsonNode second) {
        assertThat(second.get("feedSeq").asLong()).isEqualTo(first.get("feedSeq").asLong() + 1);
        assertThat(withoutFeedSeq(second)).isEqualTo(withoutFeedSeq(first));
    }

    private static JsonNode withoutFeedSeq(JsonNode entry) {
        ObjectNode copy = (ObjectNode) entry.deepCopy();
        copy.remove("feedSeq");
        return copy;
    }
}
