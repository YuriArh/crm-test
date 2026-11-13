package com.acme.dispatch.auth;

import static com.acme.dispatch.support.DemoData.NORTH;
import static com.acme.dispatch.support.DemoData.NORTH_INITIATOR;
import static com.acme.dispatch.support.DemoData.NORTH_INITIATOR_LOGIN;
import static com.acme.dispatch.support.DemoData.NORTH_OPERATOR;
import static com.acme.dispatch.support.DemoData.NORTH_OPERATOR_LOGIN;
import static com.acme.dispatch.support.DemoData.PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.acme.dispatch.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@IntegrationTest
class AuthTests {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Test
    void loginReturnsCurrentUserWithoutPasswordHash() throws Exception {
        MvcResult result = login(NORTH_INITIATOR_LOGIN, PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(NORTH_INITIATOR.toString()))
                .andExpect(jsonPath("$.username").value(NORTH_INITIATOR_LOGIN))
                .andExpect(jsonPath("$.displayName").value("Инициатор (Север)"))
                .andExpect(jsonPath("$.role").value("INITIATOR"))
                .andExpect(jsonPath("$.service.id").value(NORTH.toString()))
                .andExpect(jsonPath("$.service.code").value("NORTH"))
                .andExpect(jsonPath("$.service.name").value("Служба Север"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("$2y$");
        assertThat(result.getRequest().getSession(false)).isNotNull();
    }

    @Test
    void loginIgnoresUsernameCase() throws Exception {
        login("North.Initiator", PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(NORTH_INITIATOR_LOGIN));
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        login(NORTH_INITIATOR_LOGIN, "wrong-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"))
                .andExpect(jsonPath("$.detail").value("Wrong username or password"));
    }

    @Test
    void unknownUserGetsTheSameAnswerAsWrongPassword() throws Exception {
        String wrongPassword = login(NORTH_INITIATOR_LOGIN, "wrong-password")
                .andReturn().getResponse().getContentAsString();
        String unknownUser = login("nobody", PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"))
                .andReturn().getResponse().getContentAsString();

        assertThat(unknownUser).isEqualTo(wrongPassword);
    }

    @Test
    void disabledUserGetsTheSameAnswerAsWrongPassword() throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO app_user (id, username, display_name, password_hash, role, service_id, enabled, created_at)
                        SELECT :id, 'disabled.initiator', 'Disabled', password_hash, 'INITIATOR', service_id, false, now()
                        FROM app_user WHERE id = :source""")
                .param("id", id)
                .param("source", NORTH_INITIATOR)
                .update();
        try {
            login("disabled.initiator", PASSWORD)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"))
                    .andExpect(jsonPath("$.detail").value("Wrong username or password"));
        } finally {
            jdbc.sql("DELETE FROM app_user WHERE id = :id").param("id", id).update();
        }
    }

    @Test
    void blankFieldsFailValidation() throws Exception {
        login(" ", "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", hasSize(2)));
    }

    @Test
    void loginWithoutCsrfTokenIsForbidden() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(NORTH_INITIATOR_LOGIN, PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    void meWithoutSessionIsUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void meWorksWithTheLoginSession() throws Exception {
        MockHttpSession session = loginSession(NORTH_OPERATOR_LOGIN);

        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(NORTH_OPERATOR.toString()))
                .andExpect(jsonPath("$.username").value(NORTH_OPERATOR_LOGIN))
                .andExpect(jsonPath("$.role").value("OPERATOR"))
                .andExpect(jsonPath("$.service.code").value("NORTH"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void sessionStoresPrincipalWithoutPasswordHash() throws Exception {
        MockHttpSession session = loginSession(NORTH_INITIATOR_LOGIN);

        Object context = session.getAttribute("SPRING_SECURITY_CONTEXT");
        assertThat(context).isInstanceOf(SecurityContext.class);
        Object principal = ((SecurityContext) context)
                .getAuthentication().getPrincipal();
        assertThat(principal).isInstanceOfSatisfying(CurrentUser.class,
                user -> assertThat(user.passwordHash()).isNull());
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        MockHttpSession session = loginSession(NORTH_INITIATOR_LOGIN);

        mockMvc.perform(post("/api/auth/logout").session(session).with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(session.isInvalid()).isTrue();
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void logoutWithoutSessionIsUnauthenticated() throws Exception {
        mockMvc.perform(post("/api/auth/logout").with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    /**
     * The token from {@code /api/auth/csrf}, sent back in the header, lets a real login through.
     * <p>
     * Note: once any test uses {@code csrf()}, Spring Security Test swaps the shared CsrfFilter's repository
     * for a session-backed test one, so here the token may live in the session instead of the
     * {@code XSRF-TOKEN} cookie and is read from {@code X-CSRF-TOKEN}. We send the session, the cookie and both
     * headers back; the cookie flow itself is checked by the e2e tests.
     */
    @Test
    void csrfEndpointTokenIsAcceptedByLogin() throws Exception {
        MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headerName").value("X-XSRF-TOKEN"))
                .andExpect(jsonPath("$.token").value(not(emptyOrNullString())))
                .andReturn();
        String token = JsonPath.read(csrfResult.getResponse().getContentAsString(), "$.token");
        Cookie cookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");
        if (cookie != null) {
            assertThat(cookie.getValue()).isEqualTo(token);
        }

        MockHttpServletRequestBuilder login = post("/api/auth/login")
                .header("X-XSRF-TOKEN", token)
                .header("X-CSRF-TOKEN", token) // header name of the session-backed test repository
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials(NORTH_INITIATOR_LOGIN, PASSWORD));
        MockHttpSession session = (MockHttpSession) csrfResult.getRequest().getSession(false);
        if (session != null) {
            login.session(session);
        }
        if (cookie != null) {
            login.cookie(cookie);
        }
        mockMvc.perform(login)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(NORTH_INITIATOR_LOGIN));
    }

    @Test
    void operatorSeesOperatorsOfOwnService() throws Exception {
        MockHttpSession session = loginSession(NORTH_OPERATOR_LOGIN);

        mockMvc.perform(get("/api/users/operators").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(NORTH_OPERATOR.toString()))
                .andExpect(jsonPath("$[0].username").value(NORTH_OPERATOR_LOGIN))
                .andExpect(jsonPath("$[0].displayName").value("Оператор (Север)"))
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist());
    }

    @Test
    void initiatorCannotListOperators() throws Exception {
        MockHttpSession session = loginSession(NORTH_INITIATOR_LOGIN);

        mockMvc.perform(get("/api/users/operators").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    private ResultActions login(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials(username, password)));
    }

    private MockHttpSession loginSession(String username) throws Exception {
        MvcResult result = login(username, PASSWORD).andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        return session;
    }

    private static String credentials(String username, String password) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
    }
}
