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
import java.util.function.Predicate;
import java.util.function.Supplier;

import tools.jackson.databind.JsonNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code delayed} fault mode on the running stack (docs/specs/carrier-faults.md §3.4, §8): the carrier executes
 * at once but publishes the feed entries only after the configured delay (15 s on the Docker stack), the final
 * result first and the late {@code IN_PROGRESS} after it.
 */
class DelayedFaultE2ETest {

    static final String DEADLINE = LocalDate.now(ZoneId.of("Europe/Moscow")).plusDays(7).toString();
    static final Duration EMULATOR_TIMEOUT = Duration.ofSeconds(60);
    static final Duration DISPATCH_TIMEOUT = Duration.ofSeconds(90);
    static final Duration STEP_TIMEOUT = Duration.ofSeconds(20);

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
    void emulatorPublishesTheResultLateFinalFirst() {
        long start = lastFeedSeq();
        setFaults(true);
        String operationId = UUID.randomUUID().toString();
        assertThat(carrier.post("/operations", submitBody(operationId)).status()).isEqualTo(202);

        JsonNode executed = await("operation executed", () -> adminOperation(operationId),
                op -> op.get("state").asString().equals("EXECUTED"), STEP_TIMEOUT);
        assertThat(executed.get("faults").toString()).contains("DELAYED");
        assertThat(executed.get("resultPublishAt").isNull()).isFalse();
        assertThat(executed.get("result").get("sequence").asInt()).isEqualTo(2);
        String resultMessageId = executed.get("result").get("messageId").asString();
        assertThat(feedFor(operationId, start)).isEmpty();

        List<JsonNode> entries = await("deferred entries published", () -> feedFor(operationId, start),
                list -> list.size() >= 2, EMULATOR_TIMEOUT);

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).get("sequence").asInt()).isEqualTo(2);
        assertThat(entries.get(0).get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(entries.get(0).get("messageId").asString()).isEqualTo(resultMessageId);
        assertThat(entries.get(1).get("sequence").asInt()).isEqualTo(1);
        assertThat(entries.get(1).get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(entries.get(0).get("feedSeq").asLong()).isLessThan(entries.get(1).get("feedSeq").asLong());
        assertThat(entries.get(0).get("occurredAt").asString()).isEqualTo(executed.get("executedAt").asString());
        assertThat(adminOperation(operationId).get("resultPublishAt").isNull()).isTrue();
    }

    @Test
    void dispatchGetsTheLateResultAndKeepsIt() {
        setFaults(true);
        String id = createRequest();

        // Receipt is acknowledged right away, but the business result has not arrived yet.
        JsonNode acknowledged = await("delivery acknowledged", () -> card(id),
                c -> c.get("deliveryStatus").asString().equals("ACKNOWLEDGED"), STEP_TIMEOUT);
        assertThat(acknowledged.get("businessStatus").asString()).isIn("AWAITING", "IN_PROGRESS");
        String operationId = acknowledged.get("operationId").asString();
        setFaults(false); // the operation remembered the mode at receipt

        JsonNode pending = await("executed by the carrier", () -> adminOperation(operationId),
                op -> op.get("state").asString().equals("EXECUTED"), STEP_TIMEOUT);
        assertThat(pending.get("faults").toString()).contains("DELAYED");

        JsonNode done = await("final result in dispatch", () -> card(id),
                c -> isFinal(c.get("businessStatus").asString()), DISPATCH_TIMEOUT);
        assertThat(done.get("businessStatus").asString()).isEqualTo("SUCCEEDED");
        assertThat(done.get("result").get("trackingNumber").asString())
                .isEqualTo(pending.get("result").get("trackingNumber").asString());

        // The late IN_PROGRESS is published in the same transaction as the result; give the poller time to read it.
        assertThat(adminOperation(operationId).get("resultPublishAt").isNull()).isTrue();
        pause(Duration.ofSeconds(3));
        JsonNode after = card(id);
        assertThat(after.get("businessStatus").asString()).isEqualTo("SUCCEEDED");
        assertThat(after.get("deliveryStatus").asString()).isEqualTo("ACKNOWLEDGED");

        // Exactly one operation in the emulator for this request.
        JsonNode items = carrier.get("/admin/operations?operationId=" + operationId).body().get("items");
        assertThat(items.size()).isEqualTo(1);
        assertThat(items.get(0).get("state").asString()).isEqualTo("EXECUTED");
    }

    private String createRequest() {
        Map<String, Object> body = Map.of("cargoDescription", "E2E delayed", "weightKg", 12.5,
                "deliveryAddress", "Main st 1", "deadline", DEADLINE);
        E2eClient.Response created = initiator.post("/api/requests", body, idempotencyKey(newKey()));
        assertThat(created.status()).isEqualTo(201);
        return created.body().get("id").asString();
    }

    private JsonNode card(String requestId) {
        return initiator.get("/api/requests/" + requestId).body();
    }

    private static boolean isFinal(String businessStatus) {
        return businessStatus.equals("SUCCEEDED") || businessStatus.equals("REJECTED");
    }

    // --- emulator helpers (to be moved to CarrierClient) ---

    private void setFaults(boolean delayed) {
        String json = "{\"unavailable\":false,\"loseResponse\":false,\"duplicate\":false,\"delayed\":" + delayed + "}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(CarrierClient.BASE_URL + "/admin/faults"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(10))
                .PUT(HttpRequest.BodyPublishers.ofString(json))
                .build();
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("PUT /admin/faults: %s", response.body()).isEqualTo(200);
        } catch (IOException e) {
            throw new IllegalStateException("PUT /admin/faults failed — is carrier-emulator running? " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private JsonNode adminOperation(String operationId) {
        JsonNode items = carrier.get("/admin/operations?operationId=" + operationId).body().get("items");
        assertThat(items.size()).as("operations for %s", operationId).isEqualTo(1);
        return items.get(0);
    }

    /** Feed entries of one operation after {@code start}, in feed order (pages through the feed). */
    private List<JsonNode> feedFor(String operationId, long start) {
        List<JsonNode> entries = new ArrayList<>();
        long after = start;
        while (true) {
            JsonNode items = carrier.get("/results?limit=100&after=" + after).body().get("items");
            for (JsonNode item : items) {
                after = item.get("feedSeq").asLong();
                if (item.get("operationId").asString().equals(operationId)) {
                    entries.add(item);
                }
            }
            if (items.size() < 100) {
                return entries;
            }
        }
    }

    /** Current end of the feed, so a test reads only what was published after it started. */
    private long lastFeedSeq() {
        long after = 0;
        while (true) {
            JsonNode items = carrier.get("/results?limit=100&after=" + after).body().get("items");
            if (items.isEmpty()) {
                return after;
            }
            after = items.get(items.size() - 1).get("feedSeq").asLong();
        }
    }

    private static Map<String, Object> submitBody(String operationId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operationId", operationId);
        body.put("cargoDescription", "E2E delayed");
        body.put("weightKg", 12.5);
        body.put("deliveryAddress", "Main st 1");
        body.put("deadline", DEADLINE);
        return body;
    }

    private static <T> T await(String what, Supplier<T> probe, Predicate<T> done, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        T last = null;
        while (Instant.now().isBefore(deadline)) {
            last = probe.get();
            if (done.test(last)) {
                return last;
            }
            pause(Duration.ofMillis(500));
        }
        throw new AssertionError("Not reached within " + timeout.toSeconds() + " s: " + what + "; last: " + last);
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
