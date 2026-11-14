package com.acme.carrier.support;

import static com.acme.carrier.support.Contract.matchesContract;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** Reads the result feed in tests through {@code GET /results}, checking every page against the contract. */
public final class Feed {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Feed() {
    }

    /**
     * The last {@code feed_seq} handed out. The sequence is not reset when rows are deleted, so a test reads the
     * feed after this value to see only its own entries.
     */
    public static long lastSeq(JdbcClient jdbc) {
        return jdbc.sql("SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM feed_entry_feed_seq_seq")
                .query(Long.class).single();
    }

    /** One page of feed entries after {@code after}, in feed order. */
    public static List<JsonNode> page(MockMvc mockMvc, long after, int limit) throws Exception {
        String response = mockMvc.perform(get("/results")
                        .param("after", String.valueOf(after))
                        .param("limit", String.valueOf(limit)))
                .andExpect(status().isOk())
                .andExpect(matchesContract())
                .andReturn().getResponse().getContentAsString();
        List<JsonNode> items = new ArrayList<>();
        JSON.readTree(response).get("items").forEach(items::add);
        return items;
    }

    /** Entries of one operation among the first 100 after {@code after}, in feed order. */
    public static List<JsonNode> of(MockMvc mockMvc, long after, UUID operationId) throws Exception {
        return page(mockMvc, after, 100).stream()
                .filter(item -> item.get("operationId").asString().equals(operationId.toString()))
                .toList();
    }
}
