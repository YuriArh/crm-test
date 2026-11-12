package com.acme.carrier.support;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Builders for submit requests. */
public final class Operations {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Operations() {
    }

    /** A valid submit body; tweak fields with {@link Map#put}. */
    public static Map<String, Object> body(UUID operationId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operationId", operationId.toString());
        body.put("cargoDescription", "Pallets");
        body.put("weightKg", 12.5);
        body.put("deliveryAddress", "Main st 1");
        body.put("deadline", "2026-12-01");
        return body;
    }

    public static MockHttpServletRequestBuilder submit(Map<String, Object> body) {
        return submitJson(JSON.writeValueAsString(body));
    }

    public static MockHttpServletRequestBuilder submitJson(String json) {
        return post("/operations").contentType(MediaType.APPLICATION_JSON).content(json);
    }
}
