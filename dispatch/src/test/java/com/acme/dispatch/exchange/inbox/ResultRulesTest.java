package com.acme.dispatch.exchange.inbox;

import static com.acme.dispatch.exchange.inbox.ResultRules.Decision.APPLIED;
import static com.acme.dispatch.exchange.inbox.ResultRules.Decision.CONFLICT;
import static com.acme.dispatch.exchange.inbox.ResultRules.Decision.STALE;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.Test;

class ResultRulesTest {

    static final ResultMessage IN_PROGRESS = message(1, "IN_PROGRESS", null, null, null);
    static final ResultMessage SUCCEEDED = message(2, "SUCCEEDED", "TRK-1", "612.50", null);

    @Test
    void newerResultIsApplied() {
        assertThat(ResultRules.decide(0, null, false, IN_PROGRESS)).isEqualTo(APPLIED);
        assertThat(ResultRules.decide(1, null, false, SUCCEEDED)).isEqualTo(APPLIED);
        assertThat(ResultRules.decide(0, null, false, SUCCEEDED)).as("IN_PROGRESS may be skipped").isEqualTo(APPLIED);
    }

    @Test
    void olderResultIsStale() {
        assertThat(ResultRules.decide(2, null, true, IN_PROGRESS)).isEqualTo(STALE);
    }

    @Test
    void sameSequenceWithSameContentIsStale() {
        assertThat(ResultRules.decide(2, SUCCEEDED.content(), true, SUCCEEDED)).isEqualTo(STALE);
        assertThat(ResultRules.decide(2, message(2, "SUCCEEDED", "TRK-1", "612.5", null).content(), true, SUCCEEDED))
                .as("612.5 equals 612.50").isEqualTo(STALE);
    }

    @Test
    void sameSequenceWithOtherContentIsAConflict() {
        ResultMessage rejected = message(2, "REJECTED", null, null, "Нет");

        assertThat(ResultRules.decide(2, SUCCEEDED.content(), true, rejected)).isEqualTo(CONFLICT);
    }

    @Test
    void finalResultIsNeverReplacedAutomatically() {
        ResultMessage later = message(3, "REJECTED", null, null, "Передумали");

        assertThat(ResultRules.decide(2, null, true, later)).isEqualTo(CONFLICT);
    }

    static ResultMessage message(int sequence, String status, String tracking, String price, String reason) {
        String json = """
                {"feedSeq": 1, "messageId": "%s", "operationId": "%s", "sequence": %d, "status": "%s",
                 "trackingNumber": %s, "price": %s, "reason": %s, "occurredAt": "2026-11-12T09:00:00Z"}"""
                .formatted(UUID.randomUUID(), UUID.randomUUID(), sequence, status, quote(tracking),
                        price == null ? "null" : price, quote(reason));
        return ResultMessage.parse(JsonMapper.builder().build().readTree(json));
    }

    private static String quote(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }
}
