package com.acme.dispatch.history;

import java.util.List;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * One field of a request event: its value before and after, as text ({@code null} — no value). The list of them is
 * {@code request_event.changes} ({@code docs/specs/request-history.md} §2.2).
 */
public record Change(String field, String from, String to) {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<Change>> LIST = new TypeReference<>() {
    };

    /** The changes as stored: {@code [{"field", "from", "to"}]}. */
    public static String toJson(List<Change> changes) {
        return JSON.writeValueAsString(changes);
    }

    /** The changes read back from {@link #toJson}. */
    public static List<Change> listOf(String json) {
        return JSON.readValue(json, LIST);
    }
}
