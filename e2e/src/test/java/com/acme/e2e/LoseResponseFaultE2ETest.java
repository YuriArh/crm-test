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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The {@code loseResponse} fault mode ({@code docs/specs/carrier-faults.md} §3.2, §8): the response to the first
 * receipt of an operation is held until it is executed and the delay (stack default 10 s) has passed, so the client
 * times out after the carrier has already executed the operation. The operation is still executed only once.
 */
class LoseResponseFaultE2ETest {

    /** {@code CARRIER_LOSE_RESPONSE_DELAY} on the Docker stack. */
    static final Duration LOSE_RESPONSE_DELAY = Duration.ofSeconds(10);
    static final Duration FINAL_RESULT_TIMEOUT = Duration.ofSeconds(90);
    static final String DEADLINE = LocalDate.now(ZoneId.of("Europe/Moscow")).plusDays(7).toString();

    private static final JsonMapper JSON = JsonMapper.builder().build();

    final CarrierClient carrier = new CarrierClient();
    final E2eClient initiator = E2eClient.signedInAs("north.initiator");
    final E2eClient operator = E2eClient.signedInAs("north.operator");
    /** The held response takes longer than {@link CarrierClient}'s 10 s request timeout. */
    final HttpClient slowHttp = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    @AfterEach
    void allFaultsOff() {
        setFaults(false, false, false, false);
    }

    @Test
    void firstReceiptIsHeldUntilExecutedAndARepeatAnswersAtOnce() {
        setFaults(false, true, false, false);
        Map<String, Object> body = operationBody(UUID.randomUUID());
        String id = (String) body.get("operationId");

        Instant started = Instant.now();
        int firstStatus = postSlowly("/operations", body);
        Duration firstTook = Duration.between(started, Instant.now());

        assertThat(firstStatus).isEqualTo(202);
        assertThat(firstTook).isGreaterThanOrEqualTo(LOSE_RESPONSE_DELAY.minusMillis(200));
        // By the time the held response arrives the operation has been executed.
        assertThat(adminOperation(id).get("state").asString()).isEqualTo("EXECUTED");

        started = Instant.now();
        assertThat(carrier.post("/operations", body).status()).isEqualTo(202);
        assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(3));

        JsonNode operation = adminOperation(id);
        assertThat(operation.get("receivedCount").asInt()).isEqualTo(2);
        assertThat(operation.get("state").asString()).isEqualTo("EXECUTED");
        assertThat(operation.get("executionCount").asInt()).isEqualTo(1);
    }

    @Test
    void dispatchSurvivesTheLostResponseAndTheOperationIsExecutedOnce() {
        Set<String> before = newestOperationIds();
        setFaults(false, true, false, false);

        String requestId = createRequest();
        JsonNode card = awaitFinalCard(requestId);

        assertThat(card.get("businessStatus").asString()).isEqualTo("SUCCEEDED");
        assertThat(card.get("deliveryStatus").asString()).isEqualTo("ACKNOWLEDGED");

        // Executed at most once: exactly one new emulator operation, and it is this request's operationId.
        String operationId = card.get("operationId").asString();
        Set<String> added = newestOperationIds();
        added.removeAll(before);
        assertThat(added).containsExactly(operationId);
        JsonNode operation = adminOperation(operationId);
        assertThat(operation.get("state").asString()).isEqualTo("EXECUTED");
        assertThat(operation.get("executionCount").asInt()).isEqualTo(1);

        // dispatch never got the response to its first attempt. Receipt became known either from a resend with the
        // same operationId (answered at once) or from the result in the feed, which arrives while the first attempt
        // is still waiting for its response.
        JsonNode exchange = operator.get("/api/requests/" + requestId).body().get("exchange");
        String acknowledgedVia = exchange.get("acknowledgedVia").asString();
        assertThat(acknowledgedVia).isIn("RESPONSE", "RESULT");
        if (acknowledgedVia.equals("RESPONSE")) {
            assertThat(exchange.get("attempts").asInt()).isGreaterThanOrEqualTo(2);
            assertThat(operation.get("receivedCount").asInt()).isEqualTo(2);
        } else {
            assertThat(exchange.get("attempts").asInt()).isGreaterThanOrEqualTo(1);
            assertThat(operation.get("receivedCount").asInt()).isBetween(1, 2);
        }
    }

    // --- helpers (candidates for CarrierClient / Awaits) ---

    private void setFaults(boolean unavailable, boolean loseResponse, boolean duplicate, boolean delayed) {
        Map<String, Object> faults = new LinkedHashMap<>();
        faults.put("unavailable", unavailable);
        faults.put("loseResponse", loseResponse);
        faults.put("duplicate", duplicate);
        faults.put("delayed", delayed);
        int status = send(HttpRequest.newBuilder(carrierUri("/admin/faults"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(faults)))
                .timeout(Duration.ofSeconds(10)));
        assertThat(status).as("PUT /admin/faults").isEqualTo(200);
    }

    /** POST to the emulator with a timeout long enough for a held response. */
    private int postSlowly(String path, Object body) {
        return send(HttpRequest.newBuilder(carrierUri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .timeout(LOSE_RESPONSE_DELAY.plusSeconds(20)));
    }

    private int send(HttpRequest.Builder builder) {
        builder.header("Accept", "application/json");
        try {
            return slowHttp.send(builder.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
        } catch (IOException e) {
            throw new IllegalStateException("Request failed — is carrier-emulator running (make up)? " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static URI carrierUri(String path) {
        return URI.create(CarrierClient.BASE_URL + path);
    }

    private JsonNode adminOperation(String operationId) {
        JsonNode items = carrier.get("/admin/operations?operationId=" + operationId).body().get("items");
        assertThat(items.size()).as("emulator operations with id " + operationId).isEqualTo(1);
        return items.get(0);
    }

    /** The 100 newest emulator operations; e2e tests run one at a time, so a diff shows what one test added. */
    private Set<String> newestOperationIds() {
        return carrier.get("/admin/operations").body().get("items").valueStream()
                .map(item -> item.get("operationId").asString())
                .collect(Collectors.toSet());
    }

    private String createRequest() {
        Map<String, Object> body = Map.of("cargoDescription", "E2E lose response", "weightKg", 12.5,
                "deliveryAddress", "Main st 1", "deadline", DEADLINE);
        E2eClient.Response created = initiator.post("/api/requests", body, idempotencyKey(newKey()));
        assertThat(created.status()).isEqualTo(201);
        return created.body().get("id").asString();
    }

    private JsonNode awaitFinalCard(String requestId) {
        Instant deadline = Instant.now().plus(FINAL_RESULT_TIMEOUT);
        JsonNode card = null;
        while (Instant.now().isBefore(deadline)) {
            card = initiator.get("/api/requests/" + requestId).body();
            String status = card.get("businessStatus").asString();
            if (status.equals("SUCCEEDED") || status.equals("REJECTED")) {
                return card;
            }
            pause();
        }
        throw new AssertionError("Request " + requestId + " has no final result within "
                + FINAL_RESULT_TIMEOUT.toSeconds() + " s; last card: " + card);
    }

    private static void pause() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> operationBody(UUID operationId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operationId", operationId.toString());
        body.put("cargoDescription", "E2E lose response");
        body.put("weightKg", 12.5);
        body.put("deliveryAddress", "Main st 1");
        body.put("deadline", DEADLINE);
        return body;
    }
}
