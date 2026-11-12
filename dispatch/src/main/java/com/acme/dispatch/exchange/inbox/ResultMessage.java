package com.acme.dispatch.exchange.inbox;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * A result entry from the carrier ({@code ResultEntry} / {@code OperationResult} in carrier-api.yaml), parsed from
 * raw JSON. Parsing never throws: a malformed entry yields {@code invalidReason} so it can be recorded for review
 * instead of blocking the feed.
 */
public record ResultMessage(
        JsonNode raw,
        Long feedSeq,
        String messageId,
        UUID operationId,
        Integer sequence,
        String status,
        String trackingNumber,
        BigDecimal price,
        String reason,
        Instant occurredAt,
        String invalidReason) {

    static final Set<String> STATUSES = Set.of("IN_PROGRESS", "SUCCEEDED", "REJECTED");

    public static ResultMessage parse(JsonNode raw) {
        Long feedSeq = raw.path("feedSeq").isIntegralNumber() ? raw.get("feedSeq").asLong() : null;
        String messageId = text(raw, "messageId");
        UUID operationId = uuid(text(raw, "operationId"));
        Integer sequence = raw.path("sequence").isIntegralNumber() ? raw.get("sequence").asInt() : null;
        String status = text(raw, "status");
        String trackingNumber = text(raw, "trackingNumber");
        BigDecimal price = raw.path("price").isNumber() ? raw.get("price").decimalValue() : null;
        String reason = text(raw, "reason");
        Instant occurredAt = instant(text(raw, "occurredAt"));

        String invalid = null;
        if (messageId == null || messageId.isBlank()) {
            invalid = "messageId is missing";
        } else if (operationId == null) {
            invalid = "operationId is missing or not a UUID";
        } else if (sequence == null || sequence < 1) {
            invalid = "sequence is missing or not a positive integer";
        } else if (status == null || !STATUSES.contains(status)) {
            invalid = "status is missing or unknown: " + status;
        } else if (occurredAt == null) {
            invalid = "occurredAt is missing or not an ISO instant";
        } else if (status.equals("SUCCEEDED") && (trackingNumber == null || price == null || price.signum() < 0)) {
            invalid = "SUCCEEDED needs trackingNumber and a non-negative price";
        } else if (status.equals("REJECTED") && (reason == null || reason.isBlank())) {
            invalid = "REJECTED needs a reason";
        }
        return new ResultMessage(raw, feedSeq, messageId, operationId, sequence, status, trackingNumber, price, reason,
                occurredAt, invalid);
    }

    public boolean isValid() {
        return invalidReason == null;
    }

    public boolean isFinal() {
        return "SUCCEEDED".equals(status) || "REJECTED".equals(status);
    }

    /** Business content of the result; two messages with the same sequence must have the same content. */
    public String content() {
        return status + "|" + Objects.toString(trackingNumber, "") + "|"
                + (price == null ? "" : price.stripTrailingZeros().toPlainString()) + "|" + Objects.toString(reason, "");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.asString() : null;
    }

    private static UUID uuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Instant instant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
