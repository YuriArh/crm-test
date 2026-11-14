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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The {@code duplicate} fault mode of carrier-emulator (docs/specs/carrier-faults.md §3.3, §8): every feed entry of
 * an operation received while it is on is published twice under the same {@code messageId}; dispatch applies the
 * result once.
 */
class DuplicateFaultE2ETest {

    static final String DEADLINE = LocalDate.now(ZoneId.of("Europe/Moscow")).plusDays(7).toString();
    static final Duration TIMEOUT = Duration.ofSeconds(60);

    final CarrierClient carrier = new CarrierClient();
    final E2eClient initiator = E2eClient.signedInAs("north.initiator");

    @BeforeEach
    void allModesOff() {
        setFaults(false);
    }

    @AfterEach
    void allModesOffAgain() {
        setFaults(false);
    }

    @Test
    void emulatorPublishesEveryEntryTwiceUnderTheSameMessageId() {
        long cursor = feedEnd();
        setFaults(true);
        String id = UUID.randomUUID().toString();

        assertThat(carrier.post("/operations", operationBody(id)).status()).isEqualTo(202);
        awaitExecuted(id);

        List<JsonNode> entries = feedEntries(cursor, id);
        assertDuplicatedPairs(entries);
        JsonNode operation = adminOperation(id);
        assertThat(operation.get("faults").valueStream().map(JsonNode::asString)).containsExactly("DUPLICATE");
    }

    @Test
    void dispatchAppliesTheDuplicatedResultOnce() {
        long cursor = feedEnd();
        setFaults(true);

        String requestId = createRequest();
        JsonNode card = awaitFinalCard(requestId);

        assertThat(card.get("businessStatus").asString()).isEqualTo("SUCCEEDED");
        String operationId = card.get("operationId").asString();

        // Exactly one operation in the carrier for this request, executed once.
        JsonNode operation = adminOperation(operationId);
        assertThat(operation.get("state").asString()).isEqualTo("EXECUTED");
        assertThat(operation.get("executionCount").asInt()).isEqualTo(1);
        assertThat(operation.get("faults").valueStream().map(JsonNode::asString)).containsExactly("DUPLICATE");

        // The feed really carried each message twice.
        List<JsonNode> entries = feedEntries(cursor, operationId);
        assertDuplicatedPairs(entries);

        // The card shows the carrier's result.
        JsonNode result = operation.get("result");
        assertThat(card.get("result").get("trackingNumber").asString())
                .isEqualTo(result.get("trackingNumber").asString());
        assertThat(card.get("result").get("price").decimalValue())
                .isEqualByComparingTo(result.get("price").decimalValue());

        // Both copies are past dispatch's cursor by now; the second one changed nothing.
        sleep(Duration.ofSeconds(3));
        JsonNode later = initiator.get("/api/requests/" + requestId).body();
        assertThat(later.get("businessStatus").asString()).isEqualTo("SUCCEEDED");
        assertThat(later.get("version").asLong()).isEqualTo(card.get("version").asLong());
        assertThat(later.get("result")).isEqualTo(card.get("result"));
    }

    /** IN_PROGRESS twice, then the final entry twice: same messageId and content, adjacent feedSeq. */
    private static void assertDuplicatedPairs(List<JsonNode> entries) {
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

    private String createRequest() {
        Map<String, Object> body = Map.of("cargoDescription", "E2E duplicate fault", "weightKg", 12.5,
                "deliveryAddress", "Main st 1", "deadline", DEADLINE);
        E2eClient.Response created = initiator.post("/api/requests", body, idempotencyKey(newKey()));
        assertThat(created.status()).isEqualTo(201);
        return created.body().get("id").asString();
    }

    private JsonNode awaitFinalCard(String requestId) {
        Instant deadline = Instant.now().plus(TIMEOUT);
        JsonNode card = null;
        while (Instant.now().isBefore(deadline)) {
            card = initiator.get("/api/requests/" + requestId).body();
            String status = card.get("businessStatus").asString();
            if (status.equals("SUCCEEDED") || status.equals("REJECTED")) {
                return card;
            }
            sleep(Duration.ofMillis(300));
        }
        throw new AssertionError("Request " + requestId + " has no final result within " + TIMEOUT.toSeconds()
                + " s; last card: " + card);
    }

    private void awaitExecuted(String operationId) {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            JsonNode state = carrier.get("/operations/" + operationId).body();
            if (state.get("state").asString().equals("EXECUTED")) {
                return;
            }
            sleep(Duration.ofMillis(300));
        }
        throw new AssertionError("Operation " + operationId + " not executed within " + TIMEOUT.toSeconds() + " s");
    }

    private JsonNode adminOperation(String operationId) {
        JsonNode items = carrier.get("/admin/operations?operationId=" + operationId).body().get("items");
        assertThat(items.size()).isEqualTo(1);
        return items.get(0);
    }

    /** Feed entries of one operation after the cursor, read page by page. */
    private List<JsonNode> feedEntries(long after, String operationId) {
        List<JsonNode> entries = new ArrayList<>();
        long cursor = after;
        while (true) {
            JsonNode items = carrier.get("/results?limit=100&after=" + cursor).body().get("items");
            if (items.isEmpty()) {
                return entries;
            }
            items.valueStream().filter(item -> item.get("operationId").asString().equals(operationId))
                    .forEach(entries::add);
            cursor = items.get(items.size() - 1).get("feedSeq").asLong();
        }
    }

    /** Last feedSeq right now: the database is not empty, so tests read only what comes after. */
    private long feedEnd() {
        long after = 0;
        while (true) {
            JsonNode items = carrier.get("/results?limit=100&after=" + after).body().get("items");
            if (items.isEmpty()) {
                return after;
            }
            after = items.get(items.size() - 1).get("feedSeq").asLong();
        }
    }

    private static Map<String, Object> operationBody(String operationId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operationId", operationId);
        body.put("cargoDescription", "E2E duplicate fault");
        body.put("weightKg", 12.5);
        body.put("deliveryAddress", "Main st 1");
        body.put("deadline", "2026-12-31");
        return body;
    }

    /** {@code PUT /admin/faults}: only {@code duplicate} on, or everything off. */
    private static void setFaults(boolean duplicate) {
        String json = "{\"unavailable\":false,\"loseResponse\":false,\"duplicate\":" + duplicate
                + ",\"delayed\":false}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(CarrierClient.BASE_URL + "/admin/faults"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(10))
                .PUT(HttpRequest.BodyPublishers.ofString(json))
                .build();
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("PUT /admin/faults: " + response.body()).isEqualTo(200);
        } catch (IOException e) {
            throw new IllegalStateException("PUT /admin/faults failed — is carrier-emulator running? "
                    + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
