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

    static final String OPERATION_PATH = "/operations/" + ID;
    static final String EXECUTED = """
            {"operationId":"%s","state":"EXECUTED","receivedAt":"2026-11-12T09:15:02Z",
             "executedAt":"2026-11-12T09:15:03Z",
             "result":{"messageId":"0195f3a2-8000-7a00-8b00-000000000002","operationId":"%s","sequence":2,
                       "status":"SUCCEEDED","trackingNumber":"TRK-3F1C2B4D","price":612.50,"reason":null,
                       "occurredAt":"2026-11-12T09:15:03Z"}}""".formatted(ID, ID);

    @Test
    void lookupReturnsAcceptedOperation() {
        String body = "{\"operationId\":\"" + ID + "\",\"state\":\"ACCEPTED\",\"receivedAt\":\"2026-11-12T09:15:02Z\","
                + "\"executedAt\":null,\"result\":null}";
        respond(OPERATION_PATH, 200, body);

        LookupResult result = client.lookup(ID);

        assertThat(result).isInstanceOf(LookupResult.Found.class);
        LookupResult.Found found = (LookupResult.Found) result;
        assertThat(found.state()).isEqualTo("ACCEPTED");
        assertThat(found.result()).isNull();
        assertThat(found.raw()).isEqualTo(body);
    }

    @Test
    void lookupReturnsExecutedOperationWithResult() {
        respond(OPERATION_PATH, 200, EXECUTED);

        LookupResult.Found found = (LookupResult.Found) client.lookup(ID);

        assertThat(found.state()).isEqualTo("EXECUTED");
        assertThat(found.result().get("trackingNumber").asString()).isEqualTo("TRK-3F1C2B4D");
        assertThat(found.result().get("price").decimalValue()).isEqualByComparingTo("612.50");
        assertThat(found.raw()).isEqualTo(EXECUTED);
    }

    @Test
    void lookupNotFoundKeepsCarrierCodeAndMessage() {
        String body = "{\"code\":\"OPERATION_NOT_FOUND\",\"message\":\"Operation " + ID + " is not known\"}";
        respond(OPERATION_PATH, 404, body);

        assertThat(client.lookup(ID)).isEqualTo(
                new LookupResult.NotFound("OPERATION_NOT_FOUND", "Operation " + ID + " is not known", body));
    }

    @Test
    void lookupNotFoundWithBrokenBody() {
        respond(OPERATION_PATH, 404, "<html>nope</html>");

        assertThat(client.lookup(ID)).isEqualTo(new LookupResult.NotFound(null, null, "<html>nope</html>"));
    }

    @Test
    void lookupUnavailableThrows() {
        respond(OPERATION_PATH, 503, "{\"code\":\"UNAVAILABLE\",\"message\":\"down\"}");
        assertThatThrownBy(() -> client.lookup(ID)).isInstanceOf(CarrierUnavailableException.class)
                .hasMessageContaining("503");

        server.removeContext(OPERATION_PATH);
        respond(OPERATION_PATH, 500, "boom");
        assertThatThrownBy(() -> client.lookup(ID)).isInstanceOf(CarrierUnavailableException.class);
    }

    @Test
    void lookupTimeoutThrows() {
        server.createContext(OPERATION_PATH, exchange -> {
            sleep(Duration.ofMillis(1500));
            send(exchange, 200, EXECUTED);
        });

        assertThatThrownBy(() -> client.lookup(ID)).isInstanceOf(CarrierUnavailableException.class);
    }

    @Test
    void lookupRefusedConnectionThrows() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        HttpCarrierClient unreachable = clientFor(URI.create("http://127.0.0.1:" + closedPort), Duration.ofMillis(500));

        assertThatThrownBy(() -> unreachable.lookup(ID)).isInstanceOf(CarrierUnavailableException.class);
    }

    @Test
    void lookupForeignOperationIsInvalid() {
        UUID other = UUID.fromString("0195f3a2-7c41-7d10-9a5e-000000000000");
        respond(OPERATION_PATH, 200, EXECUTED.replaceFirst(ID.toString(), other.toString()));

        LookupResult.Invalid invalid = (LookupResult.Invalid) client.lookup(ID);

        assertThat(invalid.reason()).contains(other.toString()).contains(ID.toString());
        assertThat(invalid.raw()).contains(other.toString());
    }

    @Test
    void lookupResultOfOtherOperationIsInvalid() {
        UUID other = UUID.fromString("0195f3a2-7c41-7d10-9a5e-000000000000");
        int last = EXECUTED.lastIndexOf(ID.toString());
        respond(OPERATION_PATH, 200, EXECUTED.substring(0, last) + other + EXECUTED.substring(last + 36));

        assertThat(((LookupResult.Invalid) client.lookup(ID)).reason()).contains("result").contains(other.toString());
    }

    @Test
    void lookupUnknownStateIsInvalid() {
        respond(OPERATION_PATH, 200, EXECUTED.replace("\"EXECUTED\"", "\"LOST\""));

        assertThat(((LookupResult.Invalid) client.lookup(ID)).reason()).contains("state");
    }

    @Test
    void lookupWithoutResultFieldIsInvalid() {
        respond(OPERATION_PATH, 200, "{\"operationId\":\"" + ID + "\",\"state\":\"ACCEPTED\"}");

        assertThat(((LookupResult.Invalid) client.lookup(ID)).reason()).contains("result");
    }

    @Test
    void lookupNotJsonIsInvalid() {
        respond(OPERATION_PATH, 200, "not json");

        LookupResult.Invalid invalid = (LookupResult.Invalid) client.lookup(ID);
        assertThat(invalid.reason()).contains("JSON");
        assertThat(invalid.raw()).isEqualTo("not json");
    }

    @Test
    void lookupUnexpectedStatusIsInvalid() {
        respond(OPERATION_PATH, 400, "{\"code\":\"VALIDATION_FAILED\",\"message\":\"bad id\"}");

        LookupResult.Invalid invalid = (LookupResult.Invalid) client.lookup(ID);
        assertThat(invalid.reason()).contains("400");
        assertThat(invalid.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(invalid.message()).isEqualTo("bad id");
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
