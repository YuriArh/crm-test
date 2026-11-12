package com.acme.e2e;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Plain JSON client for carrier-emulator (no session, no CSRF: the emulator has no authentication). */
public final class CarrierClient {

    public static final String BASE_URL = System.getProperty("e2e.carrierUrl", "http://localhost:8081");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public E2eClient.Response get(String path) {
        return send(HttpRequest.newBuilder(uri(path)).GET());
    }

    public E2eClient.Response post(String path, Object body) {
        return send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))));
    }

    private E2eClient.Response send(HttpRequest.Builder builder) {
        builder.header("Accept", "application/json").timeout(Duration.ofSeconds(10));
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
