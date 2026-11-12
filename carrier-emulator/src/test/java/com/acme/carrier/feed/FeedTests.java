package com.acme.carrier.feed;

import static com.acme.carrier.support.Contract.matchesContract;
import static com.acme.carrier.support.Operations.body;
import static com.acme.carrier.support.Operations.submit;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.acme.carrier.execution.Executor;
import com.acme.carrier.support.IntegrationTest;
import com.acme.carrier.support.TestRows;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class FeedTests {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    Executor executor;
    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void cleanUp() {
        TestRows.deleteAll(jdbc);
    }

    @Test
    void emptyFeedHasNoItems() throws Exception {
        mockMvc.perform(get("/results").param("after", String.valueOf(lastSeq())))
                .andExpect(status().isOk())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void feedReturnsEntriesAfterTheCursorInOrder() throws Exception {
        long start = lastSeq();
        UUID id = UUID.randomUUID();
        mockMvc.perform(submit(body(id))).andExpect(status().isAccepted());
        executor.runOnce();

        mockMvc.perform(get("/results").param("after", String.valueOf(start)))
                .andExpect(status().isOk())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].feedSeq").value(start + 1))
                .andExpect(jsonPath("$.items[0].operationId").value(id.toString()))
                .andExpect(jsonPath("$.items[0].sequence").value(1))
                .andExpect(jsonPath("$.items[0].status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.items[0].trackingNumber").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.items[1].feedSeq").value(start + 2))
                .andExpect(jsonPath("$.items[1].sequence").value(2))
                .andExpect(jsonPath("$.items[1].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.items[1].price").value(612.5))
                .andExpect(jsonPath("$.items[1].occurredAt").value("2026-11-12T09:00:00Z"));

        mockMvc.perform(get("/results").param("after", String.valueOf(start + 1)))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].feedSeq").value(start + 2));
    }

    @Test
    void limitBoundsThePage() throws Exception {
        long start = lastSeq();
        mockMvc.perform(submit(body(UUID.randomUUID()))).andExpect(status().isAccepted());
        mockMvc.perform(submit(body(UUID.randomUUID()))).andExpect(status().isAccepted());
        executor.runOnce();

        mockMvc.perform(get("/results").param("after", String.valueOf(start)).param("limit", "3"))
                .andExpect(status().isOk())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.items.length()").value(3));
    }

    @Test
    void invalidParametersAreRejected() throws Exception {
        mockMvc.perform(get("/results").param("limit", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("limit: must be less than or equal to 100"));
        mockMvc.perform(get("/results").param("after", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(matchesContract());
        mockMvc.perform(get("/results").param("after", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.message").value("after: has a wrong format"));
    }

    /** The feed sequence is not reset when rows are deleted, so each test reads after its current value. */
    private long lastSeq() {
        return jdbc.sql("SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM feed_entry_feed_seq_seq")
                .query(Long.class).single();
    }
}
