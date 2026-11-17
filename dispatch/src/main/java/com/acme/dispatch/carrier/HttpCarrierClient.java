package com.acme.dispatch.carrier;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** {@link CarrierClient} over HTTP, following {@code contracts/carrier-api.yaml}. */
@Component
public class HttpCarrierClient implements CarrierClient {

    private static final int MAX_ERROR_LENGTH = 500;
    private static final Set<String> STATES = Set.of("ACCEPTED", "EXECUTED");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient rest;

    public HttpCarrierClient(CarrierProperties properties) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(http);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.rest = RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public SubmitResult submit(SubmitRequest request) {
        try {
            return rest.post()
                    .uri("/operations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .exchange((httpRequest, response) -> classify(response.getStatusCode().value(), body(response.getBody())));
        } catch (ResourceAccessException e) {
            return classify(e);
        } catch (RestClientException e) {
            // Anything else after the request was built: we cannot tell whether the carrier got it.
            return SubmitResult.of(SubmitOutcome.UNKNOWN, null, describe(e));
        }
    }

    @Override
    public List<JsonNode> results(long after, int limit) {
        JsonNode page;
        try {
            page = rest.get()
                    .uri(uri -> uri.path("/results").queryParam("after", after).queryParam("limit", limit).build())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new CarrierUnavailableException("Cannot read the result feed: " + describe(e), e);
        }
        if (page == null || !page.path("items").isArray()) {
            throw new CarrierUnavailableException("Result feed page has no items array", null);
        }
        List<JsonNode> items = new ArrayList<>();
        page.get("items").forEach(items::add);
        return items;
    }

    @Override
    public LookupResult lookup(UUID operationId) {
        Answer answer;
        try {
            answer = rest.get()
                    .uri("/operations/{id}", operationId)
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((httpRequest, response) -> new Answer(response.getStatusCode().value(),
                            new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
        } catch (RestClientException e) {
            throw new CarrierUnavailableException("Cannot look up operation " + operationId + ": " + describe(e), e);
        }
        if (answer.status() >= 500) {
            throw new CarrierUnavailableException("Cannot look up operation " + operationId + ": HTTP "
                    + answer.status(), null);
        }
        return classifyLookup(operationId, answer.status(), answer.body());
    }

    /** The envelope check of {@code OperationState} (carrier-api.yaml); the result inside is checked by its handler. */
    static LookupResult classifyLookup(UUID operationId, int status, String body) {
        JsonNode json = readJson(body);
        if (status == 404) {
            return new LookupResult.NotFound(errorField(json, "code"), errorField(json, "message"), body);
        }
        if (status != 200) {
            return new LookupResult.Invalid("Unexpected HTTP " + status, errorField(json, "code"),
                    errorField(json, "message"), body);
        }
        if (json == null || !json.isObject()) {
            return invalid("Body is not a JSON object", body);
        }
        String id = json.path("operationId").isString() ? json.get("operationId").asString() : null;
        if (!operationId.toString().equals(id)) {
            return invalid("operationId " + quote(id) + " does not match " + operationId, body);
        }
        String state = json.path("state").isString() ? json.get("state").asString() : null;
        if (!STATES.contains(state)) {
            return invalid("state is missing or unknown: " + quote(state), body);
        }
        JsonNode result = json.get("result");
        if (result == null) {
            return invalid("result is missing", body);
        }
        if (result.isNull()) {
            return new LookupResult.Found(state, null, body);
        }
        if (!result.isObject()) {
            return invalid("result is neither an object nor null", body);
        }
        String resultOperation = result.path("operationId").isString() ? result.get("operationId").asString() : null;
        if (!operationId.toString().equals(resultOperation)) {
            return invalid("result operationId " + quote(resultOperation) + " does not match " + operationId, body);
        }
        return new LookupResult.Found(state, result, body);
    }

    private static LookupResult invalid(String reason, String body) {
        return new LookupResult.Invalid(reason, null, null, body);
    }

    private static JsonNode readJson(String body) {
        try {
            return JSON.readTree(body);
        } catch (JacksonException e) {
            return null;
        }
    }

    /** A text field of a {@code {code, message}} error body, shortened; {@code null} if there is none. */
    private static String errorField(JsonNode json, String field) {
        if (json == null || !json.path(field).isString()) {
            return null;
        }
        String value = json.get(field).asString();
        return value.length() > MAX_ERROR_LENGTH ? value.substring(0, MAX_ERROR_LENGTH) : value;
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        return "'" + (value.length() > 100 ? value.substring(0, 100) + "…" : value) + "'";
    }

    private record Answer(int status, String body) {
    }

    static SubmitResult classify(int status, String body) {
        if (status >= 200 && status < 300) {
            return SubmitResult.of(SubmitOutcome.ACKNOWLEDGED, status, null);
        }
        String error = "HTTP " + status + (body.isBlank() ? "" : ": " + body);
        if (status == 409) {
            return SubmitResult.of(SubmitOutcome.PAYLOAD_CONFLICT, status, error);
        }
        if (status >= 500) {
            return SubmitResult.of(SubmitOutcome.TEMPORARY_ERROR, status, error);
        }
        return SubmitResult.of(SubmitOutcome.PERMANENT_ERROR, status, error);
    }

    /**
     * A connection that was never established means the request was not sent: a plain temporary error.
     * A timeout or failure after connecting means the carrier may have received it: the outcome is unknown.
     */
    static SubmitResult classify(ResourceAccessException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpConnectTimeoutException || cause instanceof ConnectException
                    || cause instanceof UnknownHostException || cause instanceof UnresolvedAddressException) {
                return SubmitResult.of(SubmitOutcome.TEMPORARY_ERROR, null, "Not sent: " + describe(cause));
            }
            if (cause instanceof HttpTimeoutException) {
                return SubmitResult.of(SubmitOutcome.UNKNOWN, null, "No response in time: " + describe(cause));
            }
        }
        return SubmitResult.of(SubmitOutcome.UNKNOWN, null, "Connection failed after sending: " + describe(e));
    }

    private static String body(InputStream stream) throws IOException {
        byte[] bytes = stream.readNBytes(MAX_ERROR_LENGTH);
        return new String(bytes, StandardCharsets.UTF_8).strip();
    }

    private static String describe(Throwable e) {
        String message = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
    }
}
