package com.acme.dispatch;

import static com.acme.dispatch.support.DemoData.NORTH_INITIATOR_LOGIN;
import static com.acme.dispatch.support.TestSecurity.asUser;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.acme.dispatch.auth.AppUserRepository;
import com.acme.dispatch.support.IntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class DispatchApplicationTests {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    AppUserRepository users;

    @Test
    void healthIsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void systemInfoExposesBusinessTimezone() throws Exception {
        mockMvc.perform(get("/api/system/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application").value("dispatch"))
                .andExpect(jsonPath("$.businessTimezone").value("Europe/Moscow"))
                .andExpect(jsonPath("$.serverTime").value("2026-11-12T09:00:00Z"));
    }

    @Test
    void protectedEndpointWithoutSessionReturnsProblemDetails() throws Exception {
        mockMvc.perform(get("/api/requests"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void unsupportedMethodHasACode() throws Exception {
        mockMvc.perform(delete("/api/requests/" + UUID.randomUUID())
                        .with(asUser(users, NORTH_INITIATOR_LOGIN)).with(csrf()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.status").value(405));
    }

    @Test
    void unsupportedMediaTypeHasACode() throws Exception {
        mockMvc.perform(post("/api/requests").with(asUser(users, NORTH_INITIATOR_LOGIN)).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void unknownPathHasACode() throws Exception {
        mockMvc.perform(get("/api/nothing-here").with(asUser(users, NORTH_INITIATOR_LOGIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("Not found"));
    }

    @Test
    void weightPrecisionErrorIsReadable() throws Exception {
        mockMvc.perform(post("/api/requests").with(asUser(users, NORTH_INITIATOR_LOGIN)).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cargoDescription\":\"x\",\"weightKg\":1.25,\"deliveryAddress\":\"a\","
                                + "\"deadline\":\"2026-11-20\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("weightKg"))
                .andExpect(jsonPath("$.errors[0].message").value("must have at most one decimal place, e.g. 12.5"));
    }
}
