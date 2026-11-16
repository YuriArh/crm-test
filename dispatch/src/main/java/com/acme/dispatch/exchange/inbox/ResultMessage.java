package com.acme.dispatch.exchange.inbox;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

/**
 * A result entry from the carrier ({@code ResultEntry} / {@code OperationResult} in carrier-api.yaml), parsed from
 * raw JSON and checked strictly against the contract ({@code docs/specs/feed-bad-records.md} §6). Parsing never
 * throws, whatever the input: a malformed entry yields {@code invalidReason}, with values from the entry escaped and
 * shortened. Numbers are checked against their range before they are converted.
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
    static final BigDecimal MAX_PRICE = new BigDecimal("9999999999.99");
    static final int MAX_REASON_LENGTH = 1000;
    /** Longest value from the entry quoted in {@link #invalidReason}. */
    static final int QUOTED_LENGTH = 200;

    private static final Pattern UUID_TEXT =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern TRACKING_NUMBER = Pattern.compile("^TRK-[0-9A-F]{8}$");

    public static ResultMessage parse(JsonNode raw) {
        Long feedSeq = longValue(raw.path("feedSeq"));
        String messageId = text(raw, "messageId");
        UUID operationId = uuid(text(raw, "operationId"));
        Integer sequence = intValue(raw.path("sequence"));
        String status = text(raw, "status");
        String trackingNumber = text(raw, "trackingNumber");
        BigDecimal price = decimal(raw.path("price"));
        String reason = text(raw, "reason");
        Instant occurredAt = instant(text(raw, "occurredAt"));

        String invalid = null;
        if (messageId == null || !UUID_TEXT.matcher(messageId).matches()) {
            invalid = "messageId is missing or not a UUID";
        } else if (operationId == null) {
            invalid = "operationId is missing or not a UUID";
        } else if (sequence == null || sequence < 1) {
            invalid = "sequence is missing or not an integer in 1.." + Integer.MAX_VALUE;
        } else if (status == null || !STATUSES.contains(status)) {
            invalid = "status is missing or unknown: " + quote(status);
        } else if (occurredAt == null) {
            invalid = "occurredAt is missing or not an ISO instant";
        } else if (trackingNumber != null && !TRACKING_NUMBER.matcher(trackingNumber).matches()) {
            invalid = "trackingNumber does not match TRK-XXXXXXXX: " + quote(trackingNumber);
        } else if (price != null && !validPrice(price)) {
            invalid = "price is not a number in 0.." + MAX_PRICE.toPlainString() + " with at most 2 decimals: "
                    + quote(price.toPlainString());
        } else if (!raw.path("price").isMissingNode() && !raw.path("price").isNull() && price == null) {
            invalid = "price is not a number in 0.." + MAX_PRICE.toPlainString() + ": " + quote(raw.path("price").toString());
        } else if (reason != null && !validReason(reason)) {
            invalid = "reason is longer than " + MAX_REASON_LENGTH + " characters or has control characters: "
                    + quote(reason);
        } else if (status.equals("SUCCEEDED") && (trackingNumber == null || price == null)) {
            invalid = "SUCCEEDED needs trackingNumber and price";
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

    private static boolean validPrice(BigDecimal price) {
        return price.signum() >= 0 && price.compareTo(MAX_PRICE) <= 0 && price.stripTrailingZeros().scale() <= 2;
    }

    private static boolean validReason(String reason) {
        return reason.length() <= MAX_REASON_LENGTH && SafeText.isPrintable(reason);
    }

    private static String quote(String value) {
        return SafeText.of(value, QUOTED_LENGTH);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.asString() : null;
    }

    private static Long longValue(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() ? value.asLong() : null;
    }

    private static Integer intValue(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToInt() ? value.asInt() : null;
    }

    /** A finite number as a decimal; {@code null} for anything else (a string, an infinite double, …). */
    private static BigDecimal decimal(JsonNode value) {
        if (!value.isNumber()) {
            return null;
        }
        if (value.isDouble() || value.isFloat()) {
            double number = value.asDouble();
            return Double.isFinite(number) ? BigDecimal.valueOf(number) : null;
        }
        return value.decimalValue();
    }

    private static UUID uuid(String value) {
        if (value == null || !UUID_TEXT.matcher(value).matches()) {
            return null;
        }
        return UUID.fromString(value);
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
