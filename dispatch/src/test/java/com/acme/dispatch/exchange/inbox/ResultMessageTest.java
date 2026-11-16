package com.acme.dispatch.exchange.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.Test;

/** Strict check of a carrier result against contracts/carrier-api.yaml (docs/specs/feed-bad-records.md §6). */
class ResultMessageTest {

    static final String OPERATION = "0195f3a2-7c41-7d10-9a5e-3f1c2b4d5e6f";
    static final String MESSAGE = "0195f3a2-8000-7a00-8b00-000000000002";

    @Test
    void validEntryIsParsed() {
        ResultMessage message = parse("""
                {"feedSeq": 42, "messageId": "%s", "operationId": "%s",
                 "sequence": 2, "status": "SUCCEEDED", "trackingNumber": "TRK-3F1C2B4D", "price": 612.50,
                 "reason": null, "occurredAt": "2026-11-12T09:15:03Z"}""".formatted(MESSAGE, OPERATION));

        assertThat(message.isValid()).isTrue();
        assertThat(message.feedSeq()).isEqualTo(42L);
        assertThat(message.operationId()).isEqualTo(UUID.fromString(OPERATION));
        assertThat(message.sequence()).isEqualTo(2);
        assertThat(message.isFinal()).isTrue();
        assertThat(message.price()).isEqualByComparingTo("612.5");
        assertThat(message.occurredAt()).isEqualTo(Instant.parse("2026-11-12T09:15:03Z"));
    }

    @Test
    void malformedEntriesHaveAReason() {
        assertThat(parse("{\"feedSeq\": 1}").invalidReason()).isEqualTo("messageId is missing or not a UUID");
        assertThat(parse(entry("\"operationId\": \"nope\"")).invalidReason()).contains("operationId");
        assertThat(parse(entry("\"sequence\": 0")).invalidReason()).contains("sequence");
        assertThat(parse(entry("\"status\": \"DONE\"")).invalidReason()).contains("status");
        assertThat(parse(entry("\"occurredAt\": \"yesterday\"")).invalidReason()).contains("occurredAt");
        assertThat(parse(entry("\"status\": \"SUCCEEDED\", \"price\": null")).invalidReason()).contains("SUCCEEDED");
        assertThat(parse(entry("\"status\": \"REJECTED\"")).invalidReason()).contains("REJECTED");
    }

    @Test
    void malformedEntryKeepsWhatCouldBeRead() {
        ResultMessage message = parse(entry("\"status\": \"DONE\""));

        assertThat(message.feedSeq()).isEqualTo(7L);
        assertThat(message.messageId()).isEqualTo(MESSAGE);
        assertThat(message.operationId()).isEqualTo(UUID.fromString(OPERATION));
    }

    @Test
    void identifiersMustBeCanonicalUuids() {
        assertThat(parse(entry("\"messageId\": \"m-7\"")).invalidReason()).isEqualTo("messageId is missing or not a UUID");
        assertThat(parse(entry("\"operationId\": \"1-1-1-1-1\"")).invalidReason()).contains("operationId");
    }

    @Test
    void sequenceStaysWithinItsType() {
        assertThat(parse(entry("\"sequence\": 2147483647")).isValid()).isTrue();
        assertThat(parse(entry("\"sequence\": 2147483648")).invalidReason()).contains("sequence");
        assertThat(parse(entry("\"sequence\": 4294967297")).invalidReason()).contains("sequence");
        assertThat(parse(entry("\"sequence\": 1.5")).invalidReason()).contains("sequence");
    }

    @Test
    void feedPositionBeyondItsTypeIsNotRead() {
        ResultMessage message = parse(entry("\"feedSeq\": 92233720368547758070"));

        assertThat(message.feedSeq()).isNull();
        assertThat(message.isValid()).as("the position is the envelope's business").isTrue();
    }

    @Test
    void priceHasAtMostTwoDecimalsAndFitsTheColumn() {
        assertThat(succeeded("9999999999.99").isValid()).isTrue();
        assertThat(succeeded("0").isValid()).isTrue();
        assertThat(succeeded("612.50").isValid()).isTrue();
        assertThat(succeeded("10000000000.00").invalidReason()).contains("price");
        assertThat(succeeded("1.234").invalidReason()).contains("price");
        assertThat(succeeded("-0.01").invalidReason()).contains("price");
    }

    @Test
    void trackingNumberFollowsTheContract() {
        assertThat(parse(successWith("\"trackingNumber\": \"TRK-0000000A\"")).isValid()).isTrue();
        assertThat(parse(successWith("\"trackingNumber\": \"TRK-1\"")).invalidReason()).contains("trackingNumber");
        assertThat(parse(successWith("\"trackingNumber\": \"TRK-0000000\\u0000\"")).invalidReason())
                .contains("trackingNumber");
    }

    @Test
    void reasonIsBoundedAndPrintable() {
        assertThat(rejected("a".repeat(1000)).isValid()).isTrue();
        assertThat(rejected("a".repeat(1001)).invalidReason()).contains("reason");
        assertThat(rejected("bad\\u0000reason").invalidReason()).contains("reason");
        assertThat(rejected("bad\\u001Freason").invalidReason()).contains("reason");
        assertThat(rejected("bad\\u007Freason").invalidReason()).contains("reason");
        assertThat(rejected("").invalidReason()).contains("REJECTED");
    }

    @Test
    void valuesInTheReasonAreEscapedAndShortened() {
        String reason = parse(entry("\"status\": \"DO\\u0000NE\"")).invalidReason();
        assertThat(reason).contains("DO\\u0000NE").doesNotContain("\u0000");

        String longStatus = parse(entry("\"status\": \"" + "X".repeat(500) + "\"")).invalidReason();
        assertThat(longStatus.length()).isLessThan(300);
    }

    @Test
    void noInputMakesParsingThrow() {
        List<String> inputs = List.of("42", "\"text\"", "[]", "null", "{}",
                entry("\"sequence\": 99999999999999999999999"),
                entry("\"feedSeq\": -1"),
                entry("\"price\": 1e400"),
                entry("\"status\": 5"),
                entry("\"occurredAt\": 7"),
                successWith("\"price\": \"612.50\""));
        for (String input : inputs) {
            assertThatCode(() -> parse(input)).as(input).doesNotThrowAnyException();
        }
    }

    /** A valid IN_PROGRESS entry with one field overridden (a later key wins). */
    private static String entry(String override) {
        String base = """
                "feedSeq": 7, "messageId": "%s", "operationId": "%s", "sequence": 1, "status": "IN_PROGRESS",
                "trackingNumber": null, "price": null, "reason": null, "occurredAt": "2026-11-12T09:15:03Z\""""
                .formatted(MESSAGE, OPERATION);
        return "{" + base + ", " + override + "}";
    }

    private static String successWith(String override) {
        return entry("\"status\": \"SUCCEEDED\", \"trackingNumber\": \"TRK-3F1C2B4D\", \"price\": 1, " + override);
    }

    private static ResultMessage succeeded(String price) {
        return parse(successWith("\"price\": " + price));
    }

    private static ResultMessage rejected(String reason) {
        return parse(entry("\"status\": \"REJECTED\", \"reason\": \"" + reason + "\""));
    }

    private static ResultMessage parse(String json) {
        return ResultMessage.parse(JsonMapper.builder().build().readTree(json));
    }
}
