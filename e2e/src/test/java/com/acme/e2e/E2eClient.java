package com.acme.e2e;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A browser-like HTTP client for one user: keeps the session cookie, sends the CSRF token
 * on mutating requests and speaks JSON. Create one client per user.
 */
public final class E2eClient {

    public static final String BASE_URL = System.getProperty("e2e.baseUrl", "http://localhost:8080");
    public static final String PASSWORD = "demo1234";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient http = HttpClient.newBuilder()
            .cookieHandler(cookies)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** A new client already signed in as {@code username} with the demo password. */
    public static E2eClient signedInAs(String username) {
        E2eClient client = new E2eClient();
        Response response = client.login(username, PASSWORD);
        if (response.status() != 200) {
            throw new IllegalStateException("Login as " + username + " failed: " + response);
        }
        return client;
    }

    public Response login(String username, String password) {
        fetchCsrfToken();
        return post("/api/auth/login", Map.of("username", username, "password", password));
    }

    /** Obtains the {@code XSRF-TOKEN} cookie, as the SPA does on start-up. */
    public void fetchCsrfToken() {
        get("/api/auth/csrf");
    }

    public Response get(String path) {
        return send(HttpRequest.newBuilder(uri(path)).GET(), Map.of());
    }

    public Response post(String path, Object body) {
        return post(path, body, Map.of());
    }

    public Response post(String path, Object body, Map<String, String> headers) {
        return send(HttpRequest.newBuilder(uri(path)).POST(jsonBody(body)), withCsrf(headers));
    }

    /** POST without the CSRF header, to check that the server rejects it. */
    public Response postWithoutCsrf(String path, Object body) {
        return send(HttpRequest.newBuilder(uri(path)).POST(jsonBody(body)), Map.of());
    }

    public Response patch(String path, Object body) {
        return send(HttpRequest.newBuilder(uri(path)).method("PATCH", jsonBody(body)), withCsrf(Map.of()));
    }

    public static Map<String, String> idempotencyKey(String key) {
        return Map.of("Idempotency-Key", key);
    }

    public static String newKey() {
        return UUID.randomUUID().toString();
    }

    private Map<String, String> withCsrf(Map<String, String> headers) {
        Map<String, String> all = new LinkedHashMap<>(headers);
        cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("XSRF-TOKEN"))
                .map(HttpCookie::getValue)
                .findFirst()
                .ifPresent(token -> all.put("X-XSRF-TOKEN", token));
        return all;
    }

    private Response send(HttpRequest.Builder builder, Map<String, String> headers) {
        builder.header("Accept", "application/json").timeout(Duration.ofSeconds(10));
        headers.forEach(builder::header);
        try {
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode body = response.body() == null || response.body().isBlank() ? null : JSON.readTree(response.body());
            return new Response(response.statusCode(), body, response.headers().map());
        } catch (IOException e) {
            throw new IllegalStateException("Request failed — is the stack running (make up)? " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static HttpRequest.BodyPublisher jsonBody(Object body) {
        return HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body));
    }

    private static URI uri(String path) {
        return URI.create(BASE_URL + path);
    }

    /** Status, parsed JSON body (or {@code null}) and headers. */
    public record Response(int status, JsonNode body, Map<String, java.util.List<String>> headers) {

        public String header(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .flatMap(entry -> entry.getValue().stream())
                    .findFirst()
                    .orElse(null);
        }

        public String code() {
            return body == null || body.get("code") == null ? null : body.get("code").asString();
        }
    }
}
