package com.acme.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class AuthE2ETest {

    @Test
    void loginMeLogout() {
        E2eClient client = new E2eClient();

        E2eClient.Response login = client.login("north.initiator", E2eClient.PASSWORD);
        assertThat(login.status()).isEqualTo(200);
        assertThat(login.body().get("username").asString()).isEqualTo("north.initiator");
        assertThat(login.body().has("passwordHash")).isFalse();

        E2eClient.Response me = client.get("/api/auth/me");
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.body().get("username").asString()).isEqualTo("north.initiator");
        assertThat(me.body().get("service").get("code").asString()).isEqualTo("NORTH");

        E2eClient.Response logout = client.post("/api/auth/logout", Map.of());
        assertThat(logout.status()).isEqualTo(204);

        E2eClient.Response afterLogout = client.get("/api/auth/me");
        assertThat(afterLogout.status()).isEqualTo(401);
        assertThat(afterLogout.code()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void wrongPasswordIsRejected() {
        E2eClient.Response response = new E2eClient().login("north.initiator", "wrong-password");

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.code()).isEqualTo("BAD_CREDENTIALS");
    }

    @Test
    void loginWithoutCsrfHeaderIsForbidden() {
        E2eClient client = new E2eClient();
        client.fetchCsrfToken();

        E2eClient.Response response = client.postWithoutCsrf("/api/auth/login",
                Map.of("username", "north.initiator", "password", E2eClient.PASSWORD));

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.code()).isEqualTo("FORBIDDEN");
    }
}
