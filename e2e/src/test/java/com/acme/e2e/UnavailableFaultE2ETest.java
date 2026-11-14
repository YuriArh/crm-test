package com.acme.e2e;

import static com.acme.e2e.E2eClient.idempotencyKey;
import static com.acme.e2e.E2eClient.newKey;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Fault mode {@code unavailable} of carrier-emulator (docs/specs/carrier-faults.md §3.1, §8): the exchange
 * endpoints answer {@code 503}; dispatch keeps the request in {@code RETRY_WAIT} and delivers it once the mode is
 * switched off, with the same {@code operationId}.
 */
class UnavailableFaultE2ETest {

    static final String DEADLINE = LocalDate.now(ZoneId.of("Europe/Moscow")).plusDays(7).toString();
    static final Duration RETRY_WAIT_TIMEOUT = Duration.ofSeconds(30);
    static final Duration RESULT_TIMEOUT = Duration.ofSeconds(90);

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    final CarrierClient carrier = new CarrierClient();

    @BeforeEach
    void allModesOff() {
        setFaults(false);
    }

    @AfterEach
    void allModesOffAgain() {
        setFaults(false);
    }

    @Test
    void exchangeEndpointsAnswer503WhileServiceEndpointsWork() {
        setFaults(true);

        UUID operationId = UUID.randomUUID();
        E2eClient.Response submit = carrier.post("/operations", submitBody(operationId));
        assertThat(submit.status()).isEqualTo(503);
        assertThat(submit.code()).isEqualTo("UNAVAILABLE");
        assertThat(submit.body().get("message").asString()).isEqualTo("Carrier is temporarily unavailable");

        E2eClient.Response feed = carrier.get("/results?after=0");
        assertThat(feed.status()).isEqualTo(503);
        assertThat(feed.code()).isEqualTo("UNAVAILABLE");

        assertThat(carrier.get("/operations/" + operationId).status()).isEqualTo(503);

        // Service endpoints are never affected, and nothing was stored.
        E2eClient.Response faults = carrier.get("/admin/faults");
        assertThat(faults.status()).isEqualTo(200);
        assertThat(faults.body().get("unavailable").asBoolean()).isTrue();
        assertThat(carrierOperation(operationId)).isNull();

        setFaults(false);

        assertThat(carrier.get("/results?after=0").status()).isEqualTo(200);
        assertThat(carrier.get("/operations/" + operationId).status()).isEqualTo(404);
    }

    @Test
    void dispatchRetriesWhileUnavailableAndDeliversOnceAfterwards() {
        E2eClient initiator = E2eClient.signedInAs("north.initiator");
        E2eClient operator = E2eClient.signedInAs("north.operator");
        setFaults(true);

        String id = create(initiator);

        // The send attempts fail with 503: the delivery waits for the next attempt, with growing pauses.
        JsonNode waiting = awaitCard(operator, id, RETRY_WAIT_TIMEOUT, "RETRY_WAIT after two attempts",
                card -> card.get("deliveryStatus").asString().equals("RETRY_WAIT")
                        && card.get("exchange").get("attempts").asInt() >= 2);
        assertThat(waiting.get("businessStatus").asString()).isEqualTo("AWAITING");
        assertThat(waiting.get("exchange").get("nextAttemptAt").isNull()).isFalse();
        String operationId = waiting.get("operationId").asString();
        assertThat(carrierOperation(UUID.fromString(operationId)))
                .as("the carrier has not received the operation while unavailable").isNull();

        setFaults(false);

        JsonNode card = awaitCard(initiator, id, RESULT_TIMEOUT, "a final business result",
                c -> c.get("businessStatus").asString().equals("SUCCEEDED")
                        || c.get("businessStatus").asString().equals("REJECTED"));
        assertThat(card.get("businessStatus").asString()).isEqualTo("SUCCEEDED");
        assertThat(card.get("operationId").asString()).isEqualTo(operationId);
        assertThat(operator.get("/api/requests/" + id).body().get("deliveryStatus").asString())
                .isEqualTo("ACKNOWLEDGED");

        // Exactly one operation in the carrier, under the same operationId, executed.
        JsonNode items = carrier.get("/admin/operations?operationId=" + operationId).body().get("items");
        assertThat(items.size()).isEqualTo(1);
        JsonNode operation = items.get(0);
        assertThat(operation.get("state").asString()).isEqualTo("EXECUTED");
        assertThat(operation.get("executionCount").asInt()).isEqualTo(1);
        assertThat(operation.get("receivedCount").asInt()).isGreaterThanOrEqualTo(1);
    }

    private String create(E2eClient initiator) {
        Map<String, Object> body = Map.of("cargoDescription", "E2E unavailable", "weightKg", 12.5,
                "deliveryAddress", "Main st 1", "deadline", DEADLINE);
        E2eClient.Response created = initiator.post("/api/requests", body, idempotencyKey(newKey()));
        assertThat(created.status()).isEqualTo(201);
        return created.body().get("id").asString();
    }

    private JsonNode carrierOperation(UUID operationId) {
        JsonNode items = carrier.get("/admin/operations?operationId=" + operationId).body().get("items");
        return items.isEmpty() ? null : items.get(0);
    }

    private static Map<String, Object> submitBody(UUID operationId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operationId", operationId.toString());
        body.put("cargoDescription", "E2E unavailable");
        body.put("weightKg", 12.5);
        body.put("deliveryAddress", "Main st 1");
        body.put("deadline", DEADLINE);
        return body;
    }

    /** Polls the request card until {@code condition} holds; returns that card. */
    private static JsonNode awaitCard(E2eClient client, String requestId, Duration timeout, String what,
                                      Predicate<JsonNode> condition) {
        Instant deadline = Instant.now().plus(timeout);
        JsonNode card = null;
        while (Instant.now().isBefore(deadline)) {
            card = client.get("/api/requests/" + requestId).body();
            if (condition.test(card)) {
                return card;
            }
            pause();
        }
        throw new AssertionError("Request " + requestId + " did not reach " + what + " within "
                + timeout.toSeconds() + " s; last card: " + card);
    }

    // Helper for CarrierClient: PUT /admin/faults with only `unavailable` set (the integrator may move it).
    private static void setFaults(boolean unavailable) {
        Map<String, Object> faults = Map.of("unavailable", unavailable, "loseResponse", false,
                "duplicate", false, "delayed", false);
        HttpRequest request = HttpRequest.newBuilder(URI.create(CarrierClient.BASE_URL + "/admin/faults"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(10))
                .PUT(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(faults)))
                .build();
        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("PUT /admin/faults: %s", response.body()).isEqualTo(200);
        } catch (IOException e) {
            throw new IllegalStateException("Request failed — is carrier-emulator running (make up)? "
                    + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void pause() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
