package com.acme.dispatch.carrier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Real HTTP against a tiny in-process server: status codes, timeouts and refused connections are real. */
class HttpCarrierClientTest {

    static final UUID ID = UUID.fromString("0195f3a2-7c41-7d10-9a5e-3f1c2b4d5e6f");
    static final SubmitRequest REQUEST = new SubmitRequest(ID, "Pallets", new BigDecimal("12.5"), "Main st 1",
            LocalDate.of(2026, 12, 1));

    HttpServer server;
    HttpCarrierClient client;
    final AtomicReference<String> lastBody = new AtomicReference<>();
    final AtomicReference<String> lastQuery = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        client = clientFor(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), Duration.ofMillis(500));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void acceptedIsAcknowledgedAndBodyFollowsTheContract() {
        respond("/operations", 202, "{\"operationId\":\"" + ID + "\",\"status\":\"ACCEPTED\"}");

        SubmitResult result = client.submit(REQUEST);

        assertThat(result).isEqualTo(new SubmitResult(SubmitOutcome.ACKNOWLEDGED, 202, null));
        JsonNode sent = JsonMapper.builder().build().readTree(lastBody.get());
        assertThat(sent.get("operationId").asString()).isEqualTo(ID.toString());
        assertThat(sent.get("weightKg").isNumber()).isTrue();
        assertThat(sent.get("weightKg").decimalValue()).isEqualByComparingTo("12.5");
        assertThat(sent.get("deadline").asString()).isEqualTo("2026-12-01");
        assertThat(sent.propertyNames()).containsExactlyInAnyOrder(
                "operationId", "cargoDescription", "weightKg", "deliveryAddress", "deadline");
    }

    @Test
    void payloadMismatchIsAConflict() {
        respond("/operations", 409, "{\"code\":\"PAYLOAD_MISMATCH\",\"message\":\"other content\"}");

        SubmitResult result = client.submit(REQUEST);

        assertThat(result.outcome()).isEqualTo(SubmitOutcome.PAYLOAD_CONFLICT);
        assertThat(result.httpStatus()).isEqualTo(409);
        assertThat(result.error()).contains("PAYLOAD_MISMATCH");
    }

    @Test
    void otherClientErrorIsPermanent() {
        respond("/operations", 400, "{\"code\":\"VALIDATION_FAILED\",\"message\":\"weightKg: bad\"}");

        assertThat(client.submit(REQUEST).outcome()).isEqualTo(SubmitOutcome.PERMANENT_ERROR);
    }

    @Test
    void serverErrorIsTemporary() {
        respond("/operations", 503, "{\"code\":\"UNAVAILABLE\",\"message\":\"down\"}");

        SubmitResult result = client.submit(REQUEST);

        assertThat(result.outcome()).isEqualTo(SubmitOutcome.TEMPORARY_ERROR);
        assertThat(result.httpStatus()).isEqualTo(503);
    }

    @Test
    void noResponseInTimeIsUnknown() {
        server.createContext("/operations", exchange -> {
            sleep(Duration.ofMillis(1500));
            send(exchange, 202, "{}");
        });

        SubmitResult result = client.submit(REQUEST);

        assertThat(result.outcome()).isEqualTo(SubmitOutcome.UNKNOWN);
        assertThat(result.httpStatus()).isNull();
        assertThat(result.error()).startsWith("No response in time");
    }

    @Test
    void refusedConnectionIsTemporary() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        HttpCarrierClient unreachable = clientFor(URI.create("http://127.0.0.1:" + closedPort), Duration.ofMillis(500));

        SubmitResult result = unreachable.submit(REQUEST);

        assertThat(result.outcome()).isEqualTo(SubmitOutcome.TEMPORARY_ERROR);
        assertThat(result.error()).startsWith("Not sent");
    }

    @Test
    void feedPageIsReadAsRawEntries() {
        respond("/results", 200, """
                {"items":[{"feedSeq":41,"messageId":"m1","sequence":1,"status":"IN_PROGRESS"},
                          {"feedSeq":42,"broken":true}]}""");

        List<JsonNode> entries = client.results(40, 100);

        assertThat(lastQuery.get()).isEqualTo("after=40&limit=100");
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).get("feedSeq").asLong()).isEqualTo(41);
        assertThat(entries.get(1).get("broken").asBoolean()).isTrue();
    }

    @Test
    void unreadableFeedIsUnavailable() {
        respond("/results", 503, "{\"code\":\"UNAVAILABLE\",\"message\":\"down\"}");
        assertThatThrownBy(() -> client.results(0, 100)).isInstanceOf(CarrierUnavailableException.class);

        server.removeContext("/results");
        respond("/results", 200, "{\"nothing\":1}");
        assertThatThrownBy(() -> client.results(0, 100))
                .isInstanceOf(CarrierUnavailableException.class)
                .hasMessageContaining("no items");
    }

    private void respond(String path, int status, String body) {
        server.createContext(path, exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastQuery.set(exchange.getRequestURI().getQuery());
            send(exchange, status, body);
        });
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static HttpCarrierClient clientFor(URI baseUrl, Duration readTimeout) {
        return new HttpCarrierClient(new CarrierProperties(baseUrl, Duration.ofMillis(500), readTimeout));
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
