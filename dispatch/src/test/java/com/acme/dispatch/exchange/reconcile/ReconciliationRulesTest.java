package com.acme.dispatch.exchange.reconcile;

import static com.acme.dispatch.exchange.DeliveryStatus.ACKNOWLEDGED;
import static com.acme.dispatch.exchange.DeliveryStatus.FAILED;
import static com.acme.dispatch.exchange.DeliveryStatus.PENDING;
import static com.acme.dispatch.exchange.DeliveryStatus.RETRY_WAIT;
import static com.acme.dispatch.exchange.DeliveryStatus.SENDING;
import static com.acme.dispatch.exchange.DeliveryStatus.UNKNOWN;
import static com.acme.dispatch.exchange.reconcile.ReconciliationOutcome.CONFLICT;
import static com.acme.dispatch.exchange.reconcile.ReconciliationOutcome.ERROR;
import static com.acme.dispatch.exchange.reconcile.ReconciliationOutcome.EXTERNAL_AHEAD;
import static com.acme.dispatch.exchange.reconcile.ReconciliationOutcome.EXTERNAL_MISSING;
import static com.acme.dispatch.exchange.reconcile.ReconciliationOutcome.MATCH;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.acme.dispatch.carrier.LookupResult;
import com.acme.dispatch.exchange.DeliveryStatus;
import com.acme.dispatch.exchange.reconcile.ReconciliationRules.CarrierAnswer;
import com.acme.dispatch.exchange.reconcile.ReconciliationRules.Local;
import com.acme.dispatch.exchange.reconcile.ReconciliationRules.Previous;
import com.acme.dispatch.exchange.reconcile.ReconciliationRules.Signature;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ReconciliationRulesTest {

    static final UUID OPERATION = UUID.fromString("0195f3a2-7c41-7d10-9a5e-3f1c2b4d5e6f");
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String OURS = ReconciliationRules.localContent("SUCCEEDED", "TRK-00000001", new BigDecimal("612.50"),
            null);
    static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    static final Duration BASE = Duration.ofMinutes(5);
    static final Duration MAX = Duration.ofHours(1);

    @Test
    void unavailableIsError() {
        assertThat(decide(ACKNOWLEDGED, null, new CarrierAnswer.Unavailable("down"))).isEqualTo(ERROR);
    }

    @Test
    void invalidAnswerIsError() {
        CarrierAnswer answer = ReconciliationRules.answer(new LookupResult.Invalid("state is missing", null, null, "{}"));
        assertThat(decide(ACKNOWLEDGED, null, answer)).isEqualTo(ERROR);
    }

    @ParameterizedTest
    @EnumSource(value = DeliveryStatus.class, names = {"PENDING", "RETRY_WAIT", "SENDING"})
    void notFoundBeforeDeliveryIsMatch(DeliveryStatus delivery) {
        assertThat(decide(delivery, null, notFound())).isEqualTo(MATCH);
    }

    @ParameterizedTest
    @EnumSource(value = DeliveryStatus.class, names = {"UNKNOWN", "FAILED", "ACKNOWLEDGED"})
    void notFoundAfterDeliveryIsMissing(DeliveryStatus delivery) {
        assertThat(decide(delivery, null, notFound())).isEqualTo(EXTERNAL_MISSING);
    }

    @Test
    void knownWithoutResultOnBothSidesIsMatch() {
        assertThat(decide(ACKNOWLEDGED, null, accepted())).isEqualTo(MATCH);
    }

    @Test
    void knownWithoutResultButOursFinalIsConflict() {
        assertThat(decide(ACKNOWLEDGED, OURS, accepted())).isEqualTo(CONFLICT);
    }

    @Test
    void carrierResultAndNoneOfOursIsAhead() {
        assertThat(decide(ACKNOWLEDGED, null, executed(succeeded("612.50")))).isEqualTo(EXTERNAL_AHEAD);
    }

    @Test
    void sameResultIsMatch() {
        assertThat(decide(ACKNOWLEDGED, OURS, executed(succeeded("612.50")))).isEqualTo(MATCH);
    }

    @Test
    void differentResultIsConflict() {
        assertThat(decide(ACKNOWLEDGED, OURS, executed(succeeded("700.00")))).isEqualTo(CONFLICT);
    }

    @Test
    void samePriceInDifferentFormsMatches() {
        assertThat(decide(ACKNOWLEDGED, OURS, executed(succeeded("612.5")))).isEqualTo(MATCH);
    }

    @Test
    void differentMessageIdAndOccurredAtStillMatch() {
        JsonNode result = succeeded("612.50");
        ((tools.jackson.databind.node.ObjectNode) result).put("messageId", UUID.randomUUID().toString());
        ((tools.jackson.databind.node.ObjectNode) result).put("occurredAt", "2030-01-01T00:00:00Z");
        assertThat(decide(ACKNOWLEDGED, OURS, executed(result))).isEqualTo(MATCH);
    }

    @Test
    void rejectedWithDifferentReasonIsConflict() {
        String ours = ReconciliationRules.localContent("REJECTED", null, null, "Too heavy");
        assertThat(decide(ACKNOWLEDGED, ours, executed(rejected("Wrong address")))).isEqualTo(CONFLICT);
        assertThat(decide(ACKNOWLEDGED, ours, executed(rejected("Too heavy")))).isEqualTo(MATCH);
    }

    @Test
    void inProgressResultMeansNoResult() {
        JsonNode inProgress = entry(2, "IN_PROGRESS", null, null, null);
        CarrierAnswer answer = executed(inProgress);
        assertThat(((CarrierAnswer.Known) answer).result()).isNull();
        assertThat(decide(ACKNOWLEDGED, null, answer)).isEqualTo(MATCH);
    }

    @Test
    void brokenResultMakesTheAnswerInvalid() {
        JsonNode broken = entry(2, "SUCCEEDED", "TRK-00000001", null, null);
        CarrierAnswer answer = executed(broken);
        assertThat(answer).isInstanceOf(CarrierAnswer.Invalid.class);
        assertThat(((CarrierAnswer.Invalid) answer).reason()).contains("price");
        assertThat(decide(ACKNOWLEDGED, null, answer)).isEqualTo(ERROR);
    }

    @Test
    void carrierKnowsOnlyWhenFound() {
        assertThat(ReconciliationRules.carrierKnows(accepted())).isTrue();
        assertThat(ReconciliationRules.carrierKnows(executed(succeeded("1.00")))).isTrue();
        assertThat(ReconciliationRules.carrierKnows(notFound())).isFalse();
        assertThat(ReconciliationRules.carrierKnows(new CarrierAnswer.Unavailable("down"))).isFalse();
        assertThat(ReconciliationRules.carrierKnows(executed(entry(2, "SUCCEEDED", null, null, null)))).isFalse();
    }

    @Test
    void firstPauseIsBase() {
        assertThat(next(null, signature(MATCH, UNKNOWN), false)).isEqualTo(NOW.plus(BASE));
    }

    @Test
    void sameAnswerDoublesPause() {
        Previous previous = new Previous(NOW.minusSeconds(600), NOW.minusSeconds(300), signature(MATCH, ACKNOWLEDGED));
        assertThat(next(previous, signature(MATCH, ACKNOWLEDGED), false)).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
    }

    @Test
    void pauseStopsAtMax() {
        Previous previous = new Previous(NOW.minus(Duration.ofMinutes(40)), NOW, signature(MATCH, ACKNOWLEDGED));
        assertThat(next(previous, signature(MATCH, ACKNOWLEDGED), false)).isEqualTo(NOW.plus(MAX));
    }

    @Test
    void changedAnswerResetsPause() {
        Previous previous = new Previous(NOW.minus(Duration.ofMinutes(40)), NOW, signature(MATCH, ACKNOWLEDGED));
        assertThat(next(previous, signature(CONFLICT, ACKNOWLEDGED), false)).isEqualTo(NOW.plus(BASE));
    }

    @Test
    void unavailableKeepsBasePause() {
        Previous previous = new Previous(NOW.minus(Duration.ofMinutes(40)), NOW, signature(ERROR, ACKNOWLEDGED));
        assertThat(next(previous, signature(ERROR, ACKNOWLEDGED), true)).isEqualTo(NOW.plus(BASE));
    }

    @Test
    void sameAnswerComparesOutcomeStatesAndDelivery() {
        Signature base = new Signature(MATCH, "EXECUTED", "SUCCEEDED", ACKNOWLEDGED);
        assertThat(base).isEqualTo(new Signature(MATCH, "EXECUTED", "SUCCEEDED", ACKNOWLEDGED));
        assertThat(base).isNotEqualTo(new Signature(CONFLICT, "EXECUTED", "SUCCEEDED", ACKNOWLEDGED));
        assertThat(base).isNotEqualTo(new Signature(MATCH, "ACCEPTED", "SUCCEEDED", ACKNOWLEDGED));
        assertThat(base).isNotEqualTo(new Signature(MATCH, "EXECUTED", "REJECTED", ACKNOWLEDGED));
        assertThat(base).isNotEqualTo(new Signature(MATCH, "EXECUTED", "SUCCEEDED", FAILED));
    }

    @Test
    void detailsCombineReasonCarrierTextAndExcerpt() {
        String raw = "{\"code\":\"VALIDATION_FAILED\",\"message\":\"bad id\",\"pad\":\"" + "x".repeat(800) + "\"}";
        CarrierAnswer answer = ReconciliationRules.answer(
                new LookupResult.Invalid("Unexpected HTTP 400", "VALIDATION_FAILED", "bad id", raw));

        String details = ReconciliationRules.details(ERROR, new Local(ACKNOWLEDGED, null), answer);

        assertThat(details).contains("Unexpected HTTP 400").contains("VALIDATION_FAILED: bad id")
                .contains("{\"code\":\"VALIDATION_FAILED\"");
        assertThat(details.length()).isLessThan(800);
    }

    @Test
    void detailsOfNotFoundQuoteTheCarrier() {
        String details = ReconciliationRules.details(EXTERNAL_MISSING, new Local(ACKNOWLEDGED, null), notFound());
        assertThat(details).contains("does not know").contains("OPERATION_NOT_FOUND: Operation " + OPERATION);
    }

    @Test
    void detailsOfConflictShowBothSides() {
        String details = ReconciliationRules.details(CONFLICT, new Local(ACKNOWLEDGED, OURS), accepted());
        assertThat(details).contains("no result").contains("SUCCEEDED|TRK-00000001|612.5|");
    }

    @Test
    void detailsOfUnavailableNameTheError() {
        assertThat(ReconciliationRules.details(ERROR, new Local(PENDING, null), new CarrierAnswer.Unavailable("HTTP 503")))
                .contains("unavailable").contains("HTTP 503");
    }

    @Test
    void localContentIsEmptyWithoutFinalResult() {
        assertThat(ReconciliationRules.localContent("IN_PROGRESS", null, null, null)).isNull();
        assertThat(ReconciliationRules.localContent("AWAITING", null, null, null)).isNull();
        assertThat(OURS).isEqualTo("SUCCEEDED|TRK-00000001|612.5|");
    }

    private static ReconciliationOutcome decide(DeliveryStatus delivery, String ours, CarrierAnswer answer) {
        return ReconciliationRules.decide(new Local(delivery, ours), answer);
    }

    private static Instant next(Previous previous, Signature current, boolean unavailable) {
        return ReconciliationRules.nextCheck(previous, current, NOW, BASE, MAX, unavailable);
    }

    private static Signature signature(ReconciliationOutcome outcome, DeliveryStatus delivery) {
        return new Signature(outcome, null, null, delivery);
    }

    private static CarrierAnswer notFound() {
        String raw = "{\"code\":\"OPERATION_NOT_FOUND\",\"message\":\"Operation " + OPERATION + " is not known\"}";
        return ReconciliationRules.answer(new LookupResult.NotFound("OPERATION_NOT_FOUND",
                "Operation " + OPERATION + " is not known", raw));
    }

    private static CarrierAnswer accepted() {
        return ReconciliationRules.answer(new LookupResult.Found("ACCEPTED", null, "{\"state\":\"ACCEPTED\"}"));
    }

    private static CarrierAnswer executed(JsonNode result) {
        return ReconciliationRules.answer(new LookupResult.Found("EXECUTED", result, "{\"state\":\"EXECUTED\"}"));
    }

    private static JsonNode succeeded(String price) {
        return entry(2, "SUCCEEDED", "TRK-00000001", price, null);
    }

    private static JsonNode rejected(String reason) {
        return entry(2, "REJECTED", null, null, reason);
    }

    private static JsonNode entry(int sequence, String status, String tracking, String price, String reason) {
        return JSON.readTree("""
                {"messageId":"0195f3a2-8000-7a00-8b00-000000000002","operationId":"%s","sequence":%d,
                 "status":"%s","trackingNumber":%s,"price":%s,"reason":%s,"occurredAt":"2026-11-12T09:15:03Z"}"""
                .formatted(OPERATION, sequence, status, tracking == null ? "null" : "\"" + tracking + "\"",
                        price == null ? "null" : price, reason == null ? "null" : "\"" + reason + "\""));
    }
}
