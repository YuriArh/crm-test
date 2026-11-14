package com.acme.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Plain JSON client for carrier-emulator (no session, no CSRF: the emulator has no authentication). */
public final class CarrierClient {

    public static final String BASE_URL = System.getProperty("e2e.carrierUrl", "http://localhost:8081");

    /** {@code CARRIER_LOSE_RESPONSE_DELAY} on the Docker stack (docker-compose.yml). */
    public static final Duration LOSE_RESPONSE_DELAY = Duration.ofSeconds(10);
    /** {@code CARRIER_DELAYED_RESULT_DELAY} on the Docker stack (docker-compose.yml). */
    public static final Duration DELAYED_RESULT_DELAY = Duration.ofSeconds(15);

    /** Without a filter {@code GET /admin/operations} returns this many newest operations. */
    static final int NEWEST_OPERATIONS = 100;

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int FEED_PAGE = 100;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public E2eClient.Response get(String path) {
        return send(HttpRequest.newBuilder(uri(path)).GET(), TIMEOUT);
    }

    public E2eClient.Response post(String path, Object body) {
        return send(jsonRequest(path).POST(jsonBody(body)), TIMEOUT);
    }

    /** {@code POST /operations} that waits long enough for a response held by {@code loseResponse}. */
    public E2eClient.Response submitWaitingForHeldResponse(Map<String, Object> body) {
        return send(jsonRequest("/operations").POST(jsonBody(body)), LOSE_RESPONSE_DELAY.plusSeconds(20));
    }

    /** A valid submit body with a fresh operationId and the given (unique) cargo description. */
    public static Map<String, Object> operationBody(String cargoDescription) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operationId", UUID.randomUUID().toString());
        body.put("cargoDescription", cargoDescription);
        body.put("weightKg", 12.5);
        body.put("deliveryAddress", "Main st 1");
        body.put("deadline", LocalDate.now(ZoneId.of("Europe/Moscow")).plusDays(7).toString());
        return body;
    }

    // --- fault modes ---

    /** Fault mode switches ({@code Faults} in carrier-api.yaml). */
    public record Faults(boolean unavailable, boolean loseResponse, boolean duplicate, boolean delayed) {

        public static final Faults OFF = new Faults(false, false, false, false);
        public static final Faults UNAVAILABLE = new Faults(true, false, false, false);
        public static final Faults LOSE_RESPONSE = new Faults(false, true, false, false);
        public static final Faults DUPLICATE = new Faults(false, false, true, false);
        public static final Faults DELAYED = new Faults(false, false, false, true);
    }

    /** {@code PUT /admin/faults}; fails the test unless the emulator took the new state. */
    public void setFaults(Faults faults) {
        E2eClient.Response response = send(jsonRequest("/admin/faults").PUT(jsonBody(faults)), TIMEOUT);
        assertThat(response.status()).as("PUT /admin/faults: %s", response.body()).isEqualTo(200);
        assertThat(JSON.convertValue(response.body(), Faults.class)).isEqualTo(faults);
    }

    public void faultsOff() {
        setFaults(Faults.OFF);
    }

    // --- service view of operations ---

    /** The emulator's only operation with this id; fails the test if there is none. */
    public JsonNode adminOperation(String operationId) {
        JsonNode items = get("/admin/operations?operationId=" + operationId).body().get("items");
        assertThat(items.size()).as("emulator operations with id %s", operationId).isEqualTo(1);
        return items.get(0);
    }

    /** The operation with this id, or {@code null} if the emulator does not know it. */
    public JsonNode findAdminOperation(String operationId) {
        JsonNode items = get("/admin/operations?operationId=" + operationId).body().get("items");
        return items.isEmpty() ? null : items.get(0);
    }

    /**
     * Operations with this cargo description among the newest ones. e2e tests run one at a time and give every
     * operation a unique description, so this finds all operations one test created.
     */
    public List<JsonNode> operationsWithCargo(String cargoDescription) {
        return get("/admin/operations").body().get("items").valueStream()
                .filter(item -> item.get("cargoDescription").asString().equals(cargoDescription))
                .toList();
    }

    // --- result feed ---

    /**
     * The last {@code feedSeq} right now, so a test reads only what is published after it. Found by galloping and
     * binary search over "is there anything after N" (one entry per probe) instead of reading the whole feed.
     */
    public long feedEnd() {
        long low = 0;
        long high = 1;
        while (hasEntriesAfter(high)) {
            low = high;
            high *= 2;
        }
        // Entries after low (unless low == 0), none after high.
        while (high - low > 1) {
            long middle = low + (high - low) / 2;
            if (hasEntriesAfter(middle)) {
                low = middle;
            } else {
                high = middle;
            }
        }
        // high == low + 1: if anything follows low, it is the entry high.
        return hasEntriesAfter(low) ? high : low;
    }

    /** Feed entries of one operation after {@code after}, in feed order, paging forward to the end of the feed. */
    public List<JsonNode> feedEntries(long after, String operationId) {
        List<JsonNode> entries = new ArrayList<>();
        long cursor = after;
        while (true) {
            JsonNode items = feedPage(cursor, FEED_PAGE);
            for (JsonNode item : items) {
                cursor = item.get("feedSeq").asLong();
                if (item.get("operationId").asString().equals(operationId)) {
                    entries.add(item);
                }
            }
            if (items.size() < FEED_PAGE) {
                return entries;
            }
        }
    }

    private boolean hasEntriesAfter(long after) {
        return !feedPage(after, 1).isEmpty();
    }

    private JsonNode feedPage(long after, int limit) {
        E2eClient.Response response = get("/results?after=" + after + "&limit=" + limit);
        assertThat(response.status()).as("GET /results: %s", response.body()).isEqualTo(200);
        return response.body().get("items");
    }

    // --- transport ---

    private static HttpRequest.Builder jsonRequest(String path) {
        return HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json");
    }

    private static HttpRequest.BodyPublisher jsonBody(Object body) {
        return HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body));
    }

    private E2eClient.Response send(HttpRequest.Builder builder, Duration timeout) {
        builder.header("Accept", "application/json").timeout(timeout);
        try {
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode body = response.body() == null || response.body().isBlank() ? null : JSON.readTree(response.body());
            return new E2eClient.Response(response.statusCode(), body, response.headers().map());
        } catch (IOException e) {
            throw new IllegalStateException("Request failed — is carrier-emulator running (make up)? " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static URI uri(String path) {
        return URI.create(BASE_URL + path);
    }
}
