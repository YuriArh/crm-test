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

import tools.jackson.databind.JsonNode;

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
