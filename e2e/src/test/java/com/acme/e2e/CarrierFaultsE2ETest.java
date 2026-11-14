package com.acme.e2e;

import static com.acme.e2e.CarrierClient.DELAYED_RESULT_DELAY;
import static com.acme.e2e.CarrierClient.LOSE_RESPONSE_DELAY;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.acme.e2e.CarrierClient.Faults;

import tools.jackson.databind.JsonNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * carrier-emulator fault modes on their own, without dispatch ({@code docs/specs/carrier-faults.md} §3, §9):
 * each mode is switched on through {@code PUT /admin/faults} and its effect is observed through the emulator's
 * exchange and service API.
 */
class CarrierFaultsE2ETest {

    final CarrierClient carrier = new CarrierClient();

    @BeforeEach
    void allModesOff() {
        carrier.faultsOff();
    }

    @AfterEach
    void allModesOffAgain() {
        carrier.faultsOff();
    }

    @Test
    void unavailableRefusesTheExchangeEndpointsButNotTheServiceOnes() {
        Map<String, Object> body = CarrierClient.operationBody(uniqueCargo("unavailable"));
        String id = (String) body.get("operationId");
        carrier.setFaults(Faults.UNAVAILABLE);

        E2eClient.Response submit = carrier.post("/operations", body);
        assertThat(submit.status()).isEqualTo(503);
        assertThat(submit.code()).isEqualTo("UNAVAILABLE");
        assertThat(submit.body().get("message").asString()).isEqualTo("Carrier is temporarily unavailable");
        assertThat(carrier.get("/operations/" + id).status()).isEqualTo(503);
        E2eClient.Response feed = carrier.get("/results?after=0&limit=1");
        assertThat(feed.status()).isEqualTo(503);
        assertThat(feed.code()).isEqualTo("UNAVAILABLE");

        // Service endpoints work, and nothing was stored.
        E2eClient.Response faults = carrier.get("/admin/faults");
        assertThat(faults.status()).isEqualTo(200);
        assertThat(faults.body().get("unavailable").asBoolean()).isTrue();
        assertThat(carrier.findAdminOperation(id)).isNull();

        carrier.faultsOff();

        assertThat(carrier.get("/operations/" + id).status()).isEqualTo(404);
        assertThat(carrier.post("/operations", body).status()).isEqualTo(202);
    }

    @Test
    void loseResponseHoldsTheFirstReceiptUntilExecutedAndARepeatAnswersAtOnce() {
        Map<String, Object> body = CarrierClient.operationBody(uniqueCargo("loseResponse"));
        String id = (String) body.get("operationId");
        carrier.setFaults(Faults.LOSE_RESPONSE);

        Instant started = Instant.now();
        E2eClient.Response first = carrier.submitWaitingForHeldResponse(body);
        Duration firstTook = Duration.between(started, Instant.now());

        assertThat(first.status()).isEqualTo(202);
        assertThat(firstTook).isGreaterThanOrEqualTo(LOSE_RESPONSE_DELAY.minusMillis(200));
        // By the time the held response is released, the operation has been executed.
        assertThat(carrier.adminOperation(id).get("state").asString()).isEqualTo("EXECUTED");

        started = Instant.now();
        assertThat(carrier.post("/operations", body).status()).isEqualTo(202);
        assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(3));

        JsonNode operation = carrier.adminOperation(id);
        assertThat(operation.get("receivedCount").asInt()).isEqualTo(2);
        assertThat(operation.get("state").asString()).isEqualTo("EXECUTED");
        assertThat(operation.get("executionCount").asInt()).isEqualTo(1);
    }

    @Test
    void duplicatePublishesEveryEntryTwiceUnderTheSameMessageId() {
        long cursor = carrier.feedEnd();
        Map<String, Object> body = CarrierClient.operationBody(uniqueCargo("duplicate"));
        String id = (String) body.get("operationId");
        carrier.setFaults(Faults.DUPLICATE);
        assertThat(carrier.post("/operations", body).status()).isEqualTo(202);
        carrier.faultsOff(); // the operation remembered the mode at receipt

        List<JsonNode> entries = Awaits.until("both entries of " + id + " published twice",
                () -> carrier.feedEntries(cursor, id), list -> list.size() >= 4, Awaits.TIMEOUT);

        assertDuplicatedPairs(entries);
        JsonNode operation = carrier.adminOperation(id);
        assertThat(operation.get("faults").valueStream().map(JsonNode::asString)).containsExactly("DUPLICATE");
        assertThat(operation.get("result").get("messageId")).isEqualTo(entries.get(2).get("messageId"));
    }

    @Test
    void delayedPublishesTheResultLateFinalFirst() {
        long cursor = carrier.feedEnd();
        Map<String, Object> body = CarrierClient.operationBody(uniqueCargo("delayed"));
        String id = (String) body.get("operationId");
        carrier.setFaults(Faults.DELAYED);
        assertThat(carrier.post("/operations", body).status()).isEqualTo(202);
        carrier.faultsOff(); // the operation remembered the mode at receipt

        // Executed at once: the result is already visible through the operation, not yet in the feed.
        JsonNode executed = Awaits.until("operation " + id + " executed", () -> carrier.adminOperation(id),
                op -> op.get("state").asString().equals("EXECUTED"), Awaits.TIMEOUT);
        assertThat(executed.get("faults").valueStream().map(JsonNode::asString)).containsExactly("DELAYED");
        assertThat(executed.get("resultPublishAt").isNull()).isFalse();
        assertThat(executed.get("result").get("sequence").asInt()).isEqualTo(2);
        assertThat(carrier.feedEntries(cursor, id)).isEmpty();

        List<JsonNode> entries = Awaits.until("deferred entries of " + id + " published",
                () -> carrier.feedEntries(cursor, id), list -> list.size() >= 2,
                DELAYED_RESULT_DELAY.plus(Awaits.TIMEOUT));

        assertThat(entries).hasSize(2);
        JsonNode result = entries.get(0);
        JsonNode inProgress = entries.get(1);
        assertThat(result.get("sequence").asInt()).isEqualTo(2);
        assertThat(result.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(result.get("messageId")).isEqualTo(executed.get("result").get("messageId"));
        assertThat(result.get("occurredAt").asString()).isEqualTo(executed.get("executedAt").asString());
        assertThat(inProgress.get("sequence").asInt()).isEqualTo(1);
        assertThat(inProgress.get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(result.get("feedSeq").asLong()).isLessThan(inProgress.get("feedSeq").asLong());
        assertThat(carrier.adminOperation(id).get("resultPublishAt").isNull()).isTrue();
    }

    /** {@code IN_PROGRESS} twice, then the final entry twice: same messageId and content, adjacent feedSeq. */
    static void assertDuplicatedPairs(List<JsonNode> entries) {
        assertThat(entries).hasSize(4);
        assertThat(entries.stream().map(e -> e.get("sequence").asInt())).containsExactly(1, 1, 2, 2);
        for (int i = 0; i < 4; i += 2) {
            JsonNode first = entries.get(i);
            JsonNode second = entries.get(i + 1);
            assertThat(second.get("feedSeq").asLong()).isEqualTo(first.get("feedSeq").asLong() + 1);
            for (String field : List.of("messageId", "operationId", "sequence", "status", "trackingNumber", "price",
                    "reason", "occurredAt")) {
                assertThat(second.get(field)).as(field).isEqualTo(first.get(field));
            }
        }
        assertThat(entries.get(0).get("messageId")).isNotEqualTo(entries.get(2).get("messageId"));
    }

    /** A cargo description no other operation has, so a test can find exactly its own operations. */
    static String uniqueCargo(String mode) {
        return "E2E fault " + mode + " " + UUID.randomUUID();
    }
}
