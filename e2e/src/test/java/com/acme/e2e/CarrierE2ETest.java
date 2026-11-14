package com.acme.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

/** carrier-emulator on its own: receipt, execution in the background, result feed, counters. */
class CarrierE2ETest {

    private final CarrierClient carrier = new CarrierClient();

    @Test
    void operationIsExecutedOnceAndItsResultAppearsInTheFeed() {
        long cursor = carrier.feedEnd();
        Map<String, Object> body = body(UUID.randomUUID(), 12.5, "Main st 1");
        String id = (String) body.get("operationId");

        E2eClient.Response accepted = carrier.post("/operations", body);
        assertThat(accepted.status()).isEqualTo(202);
        assertThat(accepted.body().get("status").asString()).isEqualTo("ACCEPTED");

        JsonNode result = awaitFinalResult(id);
        assertThat(result.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(result.get("trackingNumber").asString()).matches("TRK-[0-9A-F]{8}");
        assertThat(result.get("price").decimalValue()).isEqualByComparingTo("612.50");

        // A repeat (e.g. after a lost response) is received again but not executed again.
        assertThat(carrier.post("/operations", body).status()).isEqualTo(202);
        JsonNode operation = carrier.adminOperation(id);
        assertThat(operation.get("receivedCount").asInt()).isEqualTo(2);
        assertThat(operation.get("executionCount").asInt()).isEqualTo(1);

        // The feed after our cursor has IN_PROGRESS, then the final result, for this operation.
        assertThat(carrier.feedEntries(cursor, id).stream()
                .map(item -> item.get("sequence").asInt() + ":" + item.get("status").asString()))
                .containsExactly("1:IN_PROGRESS", "2:SUCCEEDED");
    }

    @Test
    void overweightOperationIsRejected() {
        Map<String, Object> body = body(UUID.randomUUID(), 15000, "Main st 2");

        assertThat(carrier.post("/operations", body).status()).isEqualTo(202);

        JsonNode result = awaitFinalResult((String) body.get("operationId"));
        assertThat(result.get("status").asString()).isEqualTo("REJECTED");
        assertThat(result.get("reason").asString()).isEqualTo("Превышен допустимый вес");
    }

    @Test
    void sameOperationIdWithOtherContentIsAConflict() {
        UUID id = UUID.randomUUID();
        assertThat(carrier.post("/operations", body(id, 12.5, "Main st 1")).status()).isEqualTo(202);

        E2eClient.Response conflict = carrier.post("/operations", body(id, 13, "Main st 1"));

        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.code()).isEqualTo("PAYLOAD_MISMATCH");
    }

    @Test
    void unknownOperationIsNotFound() {
        E2eClient.Response response = carrier.get("/operations/" + UUID.randomUUID());

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.code()).isEqualTo("OPERATION_NOT_FOUND");
    }

    /** Polls the operation state until the executor has published its final result (executor runs every 1 s). */
    private JsonNode awaitFinalResult(String operationId) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        while (Instant.now().isBefore(deadline)) {
            JsonNode state = carrier.get("/operations/" + operationId).body();
            JsonNode result = state.get("result");
            if (result != null && !result.isNull() && result.get("sequence").asInt() == 2) {
                assertThat(state.get("state").asString()).isEqualTo("EXECUTED");
                return result;
            }
            sleep();
        }
        throw new AssertionError("No final result for " + operationId + " within 15 s");
    }

    private static Map<String, Object> body(UUID operationId, Number weight, String address) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operationId", operationId.toString());
        body.put("cargoDescription", "E2E cargo");
        body.put("weightKg", weight);
        body.put("deliveryAddress", address);
        body.put("deadline", "2026-12-31");
        return body;
    }

    private static void sleep() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
