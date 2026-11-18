package com.acme.dispatch.common;

import static com.acme.dispatch.support.DemoData.NORTH_INITIATOR_LOGIN;
import static com.acme.dispatch.support.DemoData.PASSWORD;
import static com.acme.dispatch.support.DemoData.SOUTH_INITIATOR_LOGIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import com.acme.dispatch.history.RequestEventDao;
import com.acme.dispatch.support.Contract;
import com.acme.dispatch.support.IntegrationTest;
import com.acme.dispatch.support.TestRequests;
import com.acme.dispatch.support.TestRows;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * NF10: the technical log records the operations and errors that matter for diagnosis, but never a password or an
 * access key — the session cookie, the session id behind it, the CSRF token or a password hash. Each test drives
 * the path most likely to leak at the production log level and checks the captured output.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class TechnicalLogTests {

    private static final String WRONG_PASSWORD = "Wr0ng-Secret-7f3a9c";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TestRequests requests;

    @Autowired
    RequestEventDao events;   // a spy, see IntegrationTest

    @AfterEach
    void cleanUp() {
        TestRows.deleteRequestData(jdbc);
    }

    @Test
    void successfulLoginIsLoggedWithoutPasswordOrSession(CapturedOutput output) throws Exception {
        Cookie session = login(NORTH_INITIATOR_LOGIN, PASSWORD);

        assertThat(output).contains("Login succeeded for username '" + NORTH_INITIATOR_LOGIN + "'");
        assertNoSecrets(output, PASSWORD, session);
    }

    @Test
    void failedLoginIsLoggedWithoutThePassword(CapturedOutput output) throws Exception {
        mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(NORTH_INITIATOR_LOGIN, WRONG_PASSWORD)))
                .andExpect(status().isUnauthorized());

        assertThat(output).contains("Login failed for username '" + NORTH_INITIATOR_LOGIN + "'");
        assertThat(output).doesNotContain(WRONG_PASSWORD);
    }

    @Test
    void invalidLoginBodyIsNotLoggedWithThePassword(CapturedOutput output) throws Exception {
        mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(" ", WRONG_PASSWORD)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"x\",\"password\":\"" + WRONG_PASSWORD + "\""))
                .andExpect(status().isBadRequest());

        assertThat(output).doesNotContain(WRONG_PASSWORD);
    }

    @Test
    void csrfTokenIsNotLogged(CapturedOutput output) throws Exception {
        MvcResult issued = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        String token = JsonPath.read(issued.getResponse().getContentAsString(), "$.token");

        // A refused mutating call carrying the token in the header and the cookie, as a browser sends it.
        mvc.perform(post("/api/auth/logout")
                        .header("X-XSRF-TOKEN", token + "-stale")
                        .cookie(new Cookie("XSRF-TOKEN", token)))
                .andExpect(status().isForbidden());

        assertThat(output).doesNotContain(token);
    }

    @Test
    void refusedAccessIsNotLoggedWithTheSession(CapturedOutput output) throws Exception {
        UUID foreign = requests.create("10").id();
        Cookie session = login(SOUTH_INITIATOR_LOGIN, PASSWORD);

        mvc.perform(get("/api/requests/" + foreign).cookie(session)).andExpect(status().isNotFound());
        mvc.perform(get("/api/users/operators").cookie(session)).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/logout").cookie(session)).andExpect(status().isForbidden());

        assertNoSecrets(output, PASSWORD, session);
    }

    @Test
    void unexpectedErrorIsLoggedWithoutTheSession(CapturedOutput output) throws Exception {
        UUID requestId = requests.create("10").id();
        Cookie session = login(NORTH_INITIATOR_LOGIN, PASSWORD);
        doThrow(new IllegalStateException("simulated: event refused")).when(events).insert(any());

        mvc.perform(patch("/api/requests/" + requestId).cookie(session).with(csrf())
                        .header(Contract.SKIP_HEADER, "an unexpected failure; 500 is not a documented answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"comment\":\"Fragile\"}"))
                .andExpect(status().isInternalServerError());

        assertThat(output).as("the error is in the log for diagnosis")
                .contains("Unexpected error").contains("simulated: event refused");
        assertNoSecrets(output, PASSWORD, session);
    }

    private Cookie login(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        Cookie session = result.getResponse().getCookie("SESSION");
        assertThat(session).as("the session cookie of the sign-in").isNotNull();
        return session;
    }

    private void assertNoSecrets(CapturedOutput output, String password, Cookie session) {
        String sessionId = new String(Base64.getDecoder().decode(session.getValue()), StandardCharsets.UTF_8);
        assertThat(output)
                .doesNotContain("\"password\"")
                .doesNotContain(password)
                .doesNotContain(session.getValue())
                .doesNotContain(sessionId)
                .doesNotContain("$2y$")
                .doesNotContain("$2a$");
    }

    private static String credentials(String username, String password) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
    }
}
