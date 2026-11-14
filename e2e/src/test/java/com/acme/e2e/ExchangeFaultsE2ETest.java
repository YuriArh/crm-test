package com.acme.e2e;

import static com.acme.e2e.CarrierClient.DELAYED_RESULT_DELAY;
import static com.acme.e2e.CarrierFaultsE2ETest.assertDuplicatedPairs;
import static com.acme.e2e.CarrierFaultsE2ETest.uniqueCargo;
import static com.acme.e2e.E2eClient.idempotencyKey;
import static com.acme.e2e.E2eClient.newKey;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import com.acme.e2e.CarrierClient.Faults;

import tools.jackson.databind.JsonNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The exchange through dispatch with carrier-emulator fault modes switched on ({@code docs/specs/carrier-faults.md}
 * §8). The dispatch card has no attempt history, so the course of the exchange is checked through its summary
 * ({@code exchange.attempts}, {@code acknowledgedVia}, {@code lastError}); the emulator's service API shows that the
 * operation was executed at most once.
 */
class ExchangeFaultsE2ETest {

    static final String DEADLINE = LocalDate.now(ZoneId.of("Europe/Moscow")).plusDays(7).toString();
    /** dispatch's response timeout ({@code CARRIER_READ_TIMEOUT}) and first retry pause ({@code initial-backoff}). */
    static final Duration DISPATCH_READ_TIMEOUT = Duration.ofSeconds(5);
    static final Duration RESULT_TIMEOUT = Duration.ofSeconds(90);

    final CarrierClient carrier = new CarrierClient();
    final E2eClient initiator = E2eClient.signedInAs("north.initiator");
    final E2eClient operator = E2eClient.signedInAs("north.operator");

    @BeforeEach
    void allModesOff() {
        carrier.faultsOff();
    }

    @AfterEach
    void allModesOffAgain() {
        carrier.faultsOff();
    }

    @Test
    void unavailableKeepsRetryingAndDeliversOnceAfterwards() {
        String cargo = uniqueCargo("unavailable");
        carrier.setFaults(Faults.UNAVAILABLE);
        String id = create(cargo);

        // Send attempts get 503: the delivery waits for the next attempt, with growing pauses.
        JsonNode waiting = Awaits.card(operator, id, "RETRY_WAIT after two attempts",
                card -> card.get("deliveryStatus").asString().equals("RETRY_WAIT")
                        && card.get("exchange").get("attempts").asInt() >= 2,
                Duration.ofSeconds(30));
        assertThat(waiting.get("businessStatus").asString()).isEqualTo("AWAITING");
        assertThat(waiting.get("exchange").get("nextAttemptAt").isNull()).isFalse();
        assertThat(waiting.get("exchange").get("lastError").isNull()).isFalse();
        String operationId = waiting.get("operationId").asString();
        assertThat(carrier.findAdminOperation(operationId)).as("received while unavailable").isNull();

        carrier.faultsOff();

        JsonNode card = Awaits.finalCard(operator, id, RESULT_TIMEOUT);
        assertThat(card.get("businessStatus").asString()).isEqualTo("SUCCEEDED");
        assertThat(card.get("deliveryStatus").asString()).isEqualTo("ACKNOWLEDGED");
        assertThat(card.get("operationId").asString()).isEqualTo(operationId);
        assertThat(card.get("exchange").get("lastError").isNull()).isTrue();
        JsonNode operation = assertExecutedOnce(card, cargo);
        assertThat(operation.get("receivedCount").asInt()).isEqualTo(1);
    }

    @Test
    void loseResponseAloneIsConfirmedThroughTheFeedWithoutAResend() {
        String cargo = uniqueCargo("loseResponse");
        carrier.setFaults(Faults.LOSE_RESPONSE);
        Instant created = Instant.now();
        String id = create(cargo);

        JsonNode card = Awaits.finalCard(operator, id, RESULT_TIMEOUT);
        assertThat(card.get("businessStatus").asString()).isEqualTo("SUCCEEDED");
        assertThat(card.get("exchange").get("acknowledgedVia").asString()).isEqualTo("RESULT");

        // Let the first attempt time out, and the first retry pause pass: the late timeout changes nothing and
        // nothing is resent.
        sleepUntil(created.plus(DISPATCH_READ_TIMEOUT.multipliedBy(2)).plusSeconds(2));
        JsonNode later = operator.get("/api/requests/" + id).body();
        assertThat(later.get("deliveryStatus").asString()).isEqualTo("ACKNOWLEDGED");
        assertThat(later.get("exchange").get("acknowledgedVia").asString()).isEqualTo("RESULT");
        assertThat(later.get("exchange").get("attempts").asInt()).isEqualTo(1);
        assertThat(later.get("exchange").get("nextAttemptAt").isNull()).isTrue();
        assertThat(later.get("result")).isEqualTo(card.get("result"));

        JsonNode operation = assertExecutedOnce(later, cargo);
        assertThat(operation.get("receivedCount").asInt()).isEqualTo(1);
    }

    @Test
    void loseResponseWithDelayedTimesOutResendsAndIsConfirmedByTheResponse() {
        String cargo = uniqueCargo("loseResponse+delayed");
        carrier.setFaults(new Faults(false, true, false, true));
        String id = create(cargo);

        // The held response outlives dispatch's timeout: the outcome is unknown, a resend is scheduled.
        JsonNode unknown = Awaits.card(operator, id, "UNKNOWN after the response timeout",
                card -> card.get("deliveryStatus").asString().equals("UNKNOWN"), Duration.ofSeconds(20));
        assertThat(unknown.get("exchange").get("lastError").isNull()).isFalse();
        assertThat(unknown.get("exchange").get("nextAttemptAt").isNull()).isFalse();

        // The resend with the same operationId is answered at once; the result is still held back by delayed.
        JsonNode acknowledged = Awaits.card(operator, id, "ACKNOWLEDGED after the resend",
                card -> card.get("deliveryStatus").asString().equals("ACKNOWLEDGED"), Duration.ofSeconds(20));
        assertThat(acknowledged.get("exchange").get("acknowledgedVia").asString()).isEqualTo("RESPONSE");
        assertThat(acknowledged.get("exchange").get("attempts").asInt()).isEqualTo(2);
        assertThat(acknowledged.get("exchange").get("lastError").isNull()).isTrue();
        assertThat(acknowledged.get("businessStatus").asString()).isEqualTo("AWAITING");

        JsonNode card = Awaits.finalCard(operator, id, DELAYED_RESULT_DELAY.plus(RESULT_TIMEOUT));
        assertThat(card.get("businessStatus").asString()).isEqualTo("SUCCEEDED");
        assertThat(card.get("exchange").get("acknowledgedVia").asString()).isEqualTo("RESPONSE");
        assertThat(card.get("exchange").get("attempts").asInt()).isEqualTo(2);

        JsonNode operation = assertExecutedOnce(card, cargo);
        assertThat(operation.get("receivedCount").asInt()).isEqualTo(2);
        assertThat(operation.get("faults").valueStream().map(JsonNode::asString)).containsExactly("DELAYED");
    }

    @Test
    void duplicateResultIsAppliedOnce() {
        String cargo = uniqueCargo("duplicate");
        long cursor = carrier.feedEnd();
        carrier.setFaults(Faults.DUPLICATE);
        String id = create(cargo);

        JsonNode card = Awaits.finalCard(operator, id, RESULT_TIMEOUT);
        assertThat(card.get("businessStatus").asString()).isEqualTo("SUCCEEDED");
        JsonNode operation = assertExecutedOnce(card, cargo);
        assertThat(operation.get("faults").valueStream().map(JsonNode::asString)).containsExactly("DUPLICATE");
        assertResultMatches(card, operation);

        // The feed really carried every message twice; both copies are behind dispatch's cursor a few polls later,
        // and the second one changed nothing.
        assertDuplicatedPairs(carrier.feedEntries(cursor, card.get("operationId").asString()));
        Awaits.sleep(Duration.ofSeconds(3));
        assertUnchanged(id, card);
    }

    @Test
    void delayedResultArrivesLateAndTheLateInProgressDoesNotRollItBack() {
        String cargo = uniqueCargo("delayed");
        long cursor = carrier.feedEnd();
        carrier.setFaults(Faults.DELAYED);
        String id = create(cargo);

        // Receipt is acknowledged at once; the carrier has executed, but the feed has nothing yet.
        JsonNode acknowledged = Awaits.card(operator, id, "ACKNOWLEDGED",
                card -> card.get("deliveryStatus").asString().equals("ACKNOWLEDGED"), Awaits.TIMEOUT);
        assertThat(acknowledged.get("exchange").get("acknowledgedVia").asString()).isEqualTo("RESPONSE");
        assertThat(acknowledged.get("businessStatus").asString()).isEqualTo("AWAITING");
        String operationId = acknowledged.get("operationId").asString();
        JsonNode pending = Awaits.until("operation " + operationId + " executed",
                () -> carrier.adminOperation(operationId),
                op -> op.get("state").asString().equals("EXECUTED"), Awaits.TIMEOUT);
        assertThat(pending.get("faults").valueStream().map(JsonNode::asString)).containsExactly("DELAYED");
        assertThat(pending.get("resultPublishAt").isNull()).isFalse();

        JsonNode card = Awaits.finalCard(operator, id, DELAYED_RESULT_DELAY.plus(RESULT_TIMEOUT));
        assertThat(card.get("businessStatus").asString()).isEqualTo("SUCCEEDED");
        JsonNode operation = assertExecutedOnce(card, cargo);
        assertThat(operation.get("resultPublishAt").isNull()).isTrue();
        assertResultMatches(card, operation);

        // The late IN_PROGRESS follows the result in the feed; dispatch reads it and keeps the result.
        List<JsonNode> entries = carrier.feedEntries(cursor, operationId);
        assertThat(entries).extracting(e -> e.get("sequence").asInt()).containsExactly(2, 1);
        Awaits.sleep(Duration.ofSeconds(3));
        assertUnchanged(id, card);
    }

    /**
     * Executed at most once (spec §8): exactly one emulator operation under the card's operationId, executed, and no
     * other operation with this request's unique cargo description (a resend under a new operationId would make
     * one). Returns the emulator's operation.
     */
    private JsonNode assertExecutedOnce(JsonNode card, String cargo) {
        String operationId = card.get("operationId").asString();
        JsonNode operation = carrier.adminOperation(operationId);
        assertThat(operation.get("state").asString()).isEqualTo("EXECUTED");
        assertThat(operation.get("executionCount").asInt()).isEqualTo(1);
        assertThat(carrier.operationsWithCargo(cargo))
                .extracting(op -> op.get("operationId").asString())
                .containsExactly(operationId);
        return operation;
    }

    private static void assertResultMatches(JsonNode card, JsonNode operation) {
        JsonNode result = operation.get("result");
        assertThat(card.get("result").get("trackingNumber").asString())
                .isEqualTo(result.get("trackingNumber").asString());
        assertThat(card.get("result").get("price").decimalValue())
                .isEqualByComparingTo(result.get("price").decimalValue());
    }

    private void assertUnchanged(String id, JsonNode card) {
        JsonNode later = operator.get("/api/requests/" + id).body();
        assertThat(later.get("businessStatus").asString()).isEqualTo(card.get("businessStatus").asString());
        assertThat(later.get("deliveryStatus").asString()).isEqualTo("ACKNOWLEDGED");
        assertThat(later.get("result")).isEqualTo(card.get("result"));
        assertThat(later.get("version").asLong()).isEqualTo(card.get("version").asLong());
    }

    private String create(String cargo) {
        Map<String, Object> body = Map.of("cargoDescription", cargo, "weightKg", 12.5,
                "deliveryAddress", "Main st 1", "deadline", DEADLINE);
        E2eClient.Response created = initiator.post("/api/requests", body, idempotencyKey(newKey()));
        assertThat(created.status()).isEqualTo(201);
        return created.body().get("id").asString();
    }

    private static void sleepUntil(Instant moment) {
        Duration left = Duration.between(Instant.now(), moment);
        if (left.isPositive()) {
            Awaits.sleep(left);
        }
    }
}
