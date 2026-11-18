package com.acme.dispatch.auth;

import static com.acme.dispatch.support.DemoData.NORTH;
import static com.acme.dispatch.support.DemoData.NORTH_INITIATOR;
import static com.acme.dispatch.support.DemoData.NORTH_INITIATOR_LOGIN;
import static com.acme.dispatch.support.DemoData.NORTH_OPERATOR;
import static com.acme.dispatch.support.DemoData.NORTH_OPERATOR_LOGIN;
import static com.acme.dispatch.support.DemoData.PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import com.acme.dispatch.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class AuthTests {

    /** The cookie of the session (Spring Session's default name, docs/specs/jdbc-sessions.md §2.3). */
    private static final String SESSION_COOKIE = "SESSION";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    SessionRepository<?> sessions;

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
        assertThat(result.getResponse().getCookie(SESSION_COOKIE)).isNotNull();
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
        Cookie session = loginCookie(NORTH_OPERATOR_LOGIN);

        mockMvc.perform(get("/api/auth/me").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(NORTH_OPERATOR.toString()))
                .andExpect(jsonPath("$.username").value(NORTH_OPERATOR_LOGIN))
                .andExpect(jsonPath("$.role").value("OPERATOR"))
                .andExpect(jsonPath("$.service.code").value("NORTH"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void loginStoresTheSessionInTheDatabase() throws Exception {
        Cookie session = loginCookie(NORTH_INITIATOR_LOGIN);

        Map<String, Object> row = sessionRow(session);
        assertThat(row.get("principal_name")).isEqualTo(NORTH_INITIATOR_LOGIN);
        // Thirty minutes without requests (docs/specs/jdbc-sessions.md §2.4), in milliseconds.
        assertThat((Long) row.get("expiry_time") - (Long) row.get("last_access_time")).isEqualTo(30 * 60 * 1000L);
    }

    @Test
    void sessionStoresPrincipalWithoutPasswordHash() throws Exception {
        Cookie cookie = loginCookie(NORTH_INITIATOR_LOGIN);

        Session session = sessions.findById(sessionId(cookie));

        assertThat(session).isNotNull();
        Object context = session.getAttribute("SPRING_SECURITY_CONTEXT");
        assertThat(context).isInstanceOf(SecurityContext.class);
        Object principal = ((SecurityContext) context).getAuthentication().getPrincipal();
        assertThat(principal).isInstanceOfSatisfying(CurrentUser.class,
                user -> assertThat(user.passwordHash()).isNull());
    }

    @Test
    void unreadableSessionIsUnauthenticated(CapturedOutput output) throws Exception {
        Cookie session = loginCookie(NORTH_INITIATOR_LOGIN);
        jdbc.sql("""
                        UPDATE spring_session_attributes SET attribute_bytes = :bytes
                         WHERE session_primary_id = (SELECT primary_id FROM spring_session WHERE session_id = :id)
                        """)
                .param("bytes", new byte[] {0, (byte) 0xff})
                .param("id", sessionId(session))
                .update();

        // Not a 500: the user simply signs in again, as after an expired session.
        mockMvc.perform(get("/api/auth/me").cookie(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        assertThat(output).contains("could not be read").doesNotContain(sessionId(session));
        assertThat(rowsOf(session)).as("the row stays until the clean-up").isEqualTo(1);
    }

    /**
     * Only an unreadable attribute counts as an absent session. A failure of the database must not: it would send
     * every signed-in user to the login page although their sessions are intact (docs/specs/jdbc-sessions.md §2.6).
     */
    @Test
    void databaseFailureWhileReadingASessionIsNotUnauthenticated() throws Exception {
        Cookie session = loginCookie(NORTH_INITIATOR_LOGIN);
        jdbc.sql("ALTER TABLE spring_session RENAME TO spring_session_away").update();
        try {
            assertThatThrownBy(() -> mockMvc.perform(get("/api/auth/me").cookie(session)))
                    .rootCause().hasMessageContaining("spring_session");
        } finally {
            jdbc.sql("ALTER TABLE spring_session_away RENAME TO spring_session").update();
        }
    }

    @Test
    void loginChangesTheSessionId() throws Exception {
        Cookie first = loginCookie(NORTH_INITIATOR_LOGIN);

        // Signing in again with the old cookie: the id changes, the old one stops working.
        Cookie second = login(NORTH_INITIATOR_LOGIN, PASSWORD, first)
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(SESSION_COOKIE);

        assertThat(second).isNotNull();
        assertThat(second.getValue()).isNotEqualTo(first.getValue());
        assertThat(rowsOf(first)).isZero();
        assertThat(rowsOf(second)).isEqualTo(1);
        mockMvc.perform(get("/api/auth/me").cookie(first))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/me").cookie(second))
                .andExpect(status().isOk());
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        Cookie session = loginCookie(NORTH_INITIATOR_LOGIN);

        mockMvc.perform(post("/api/auth/logout").cookie(session).with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(rowsOf(session)).isZero();
        mockMvc.perform(get("/api/auth/me").cookie(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void unknownSessionIsUnauthenticated() throws Exception {
        Cookie unknown = new Cookie(SESSION_COOKIE,
                Base64.getEncoder().encodeToString(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)));

        mockMvc.perform(get("/api/auth/me").cookie(unknown))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void expiredSessionIsUnauthenticated() throws Exception {
        Cookie session = loginCookie(NORTH_INITIATOR_LOGIN);
        // Spring Session decides by the last access plus the idle limit; expiry_time only serves the clean-up.
        jdbc.sql("UPDATE spring_session SET last_access_time = 0, expiry_time = max_inactive_interval * 1000"
                        + " WHERE session_id = :id")
                .param("id", sessionId(session)).update();

        mockMvc.perform(get("/api/auth/me").cookie(session))
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
        Cookie session = csrfResult.getResponse().getCookie(SESSION_COOKIE);
        if (session != null) {
            login.cookie(session);
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
        Cookie session = loginCookie(NORTH_OPERATOR_LOGIN);

        mockMvc.perform(get("/api/users/operators").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(NORTH_OPERATOR.toString()))
                .andExpect(jsonPath("$[0].username").value(NORTH_OPERATOR_LOGIN))
                .andExpect(jsonPath("$[0].displayName").value("Оператор (Север)"))
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist());
    }

    @Test
    void initiatorCannotListOperators() throws Exception {
        Cookie session = loginCookie(NORTH_INITIATOR_LOGIN);

        mockMvc.perform(get("/api/users/operators").cookie(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    private ResultActions login(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials(username, password)));
    }

    /** Signs in again, carrying the cookie of a session the user already has. */
    private ResultActions login(String username, String password, Cookie session) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .with(csrf())
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials(username, password)));
    }

    /** Signs in and returns the cookie of the new session, as a browser keeps it. */
    private Cookie loginCookie(String username) throws Exception {
        MvcResult result = login(username, PASSWORD).andExpect(status().isOk()).andReturn();
        Cookie session = result.getResponse().getCookie(SESSION_COOKIE);
        assertThat(session).as("the session cookie of the sign-in").isNotNull();
        return session;
    }

    /** The id Spring Session keeps in the table: the cookie carries it in Base64. */
    private static String sessionId(Cookie session) {
        return new String(Base64.getDecoder().decode(session.getValue()), StandardCharsets.UTF_8);
    }

    private Map<String, Object> sessionRow(Cookie session) {
        return jdbc.sql("SELECT principal_name, last_access_time, expiry_time FROM spring_session"
                        + " WHERE session_id = :id")
                .param("id", sessionId(session))
                .query().singleRow();
    }

    private int rowsOf(Cookie session) {
        return jdbc.sql("SELECT count(*) FROM spring_session WHERE session_id = :id")
                .param("id", sessionId(session))
                .query(Integer.class).single();
    }

    private static String credentials(String username, String password) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
    }
}
