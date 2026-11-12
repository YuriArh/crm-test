package com.acme.dispatch.exchange.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.Test;

class ResultMessageTest {

    static final String OPERATION = "0195f3a2-7c41-7d10-9a5e-3f1c2b4d5e6f";

    @Test
    void validEntryIsParsed() {
        ResultMessage message = parse("""
                {"feedSeq": 42, "messageId": "0195f3a2-8000-7a00-8b00-000000000002", "operationId": "%s",
                 "sequence": 2, "status": "SUCCEEDED", "trackingNumber": "TRK-3F1C2B4D", "price": 612.50,
                 "reason": null, "occurredAt": "2026-11-12T09:15:03Z"}""".formatted(OPERATION));

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
        assertThat(parse("{\"feedSeq\": 1}").invalidReason()).isEqualTo("messageId is missing");
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
        assertThat(message.messageId()).isEqualTo("m-7");
        assertThat(message.operationId()).isEqualTo(UUID.fromString(OPERATION));
    }

    /** A valid IN_PROGRESS entry with one field overridden. */
    private static String entry(String override) {
        String base = """
                "feedSeq": 7, "messageId": "m-7", "operationId": "%s", "sequence": 1, "status": "IN_PROGRESS",
                "trackingNumber": null, "price": null, "reason": null, "occurredAt": "2026-11-12T09:15:03Z\"""".formatted(OPERATION);
        return "{" + base + ", " + override + "}";
    }

    private static ResultMessage parse(String json) {
        return ResultMessage.parse(JsonMapper.builder().build().readTree(json));
    }
}
