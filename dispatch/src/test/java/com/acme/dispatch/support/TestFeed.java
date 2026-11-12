package com.acme.dispatch.support;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Builds carrier feed entries in the shape of {@code ResultEntry} (carrier-api.yaml). */
public final class TestFeed {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private TestFeed() {
    }

    public static JsonNode inProgress(long feedSeq, UUID operationId) {
        return entry(feedSeq, UUID.randomUUID().toString(), operationId, 1, "IN_PROGRESS", null, null, null);
    }

    public static JsonNode succeeded(long feedSeq, UUID operationId, String tracking, String price) {
        return entry(feedSeq, UUID.randomUUID().toString(), operationId, 2, "SUCCEEDED", tracking, price, null);
    }

    public static JsonNode rejected(long feedSeq, UUID operationId, String reason) {
        return entry(feedSeq, UUID.randomUUID().toString(), operationId, 2, "REJECTED", null, null, reason);
    }

    public static JsonNode entry(long feedSeq, String messageId, UUID operationId, int sequence, String status,
                                 String tracking, String price, String reason) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("feedSeq", feedSeq);
        entry.put("messageId", messageId);
        entry.put("operationId", operationId.toString());
        entry.put("sequence", sequence);
        entry.put("status", status);
        entry.put("trackingNumber", tracking);
        entry.put("price", price == null ? null : new java.math.BigDecimal(price));
        entry.put("reason", reason);
        entry.put("occurredAt", "2026-11-12T09:00:05Z");
        return JSON.valueToTree(entry);
    }

    /** The same entry at another feed position (a duplicate delivery of one message). */
    public static JsonNode atFeedSeq(JsonNode entry, long feedSeq) {
        Map<String, Object> copy = JSON.convertValue(entry, Map.class);
        copy.put("feedSeq", feedSeq);
        return JSON.valueToTree(copy);
    }

    public static JsonNode raw(String json) {
        return JSON.readTree(json);
    }
}
