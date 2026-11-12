package com.acme.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HealthE2ETest {

    @Test
    void serviceIsUp() {
        E2eClient.Response response = new E2eClient().get("/actuator/health");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().get("status").asString()).isEqualTo("UP");
    }

    @Test
    void unsupportedMethodIsProblemDetailsWithACode() {
        E2eClient client = E2eClient.signedInAs("north.initiator");

        E2eClient.Response response = client.get("/api/auth/login");

        assertThat(response.status()).isEqualTo(405);
        assertThat(response.code()).isEqualTo("METHOD_NOT_ALLOWED");
    }

    @Test
    void unknownPathIsANeutralNotFound() {
        E2eClient client = E2eClient.signedInAs("north.initiator");

        E2eClient.Response response = client.get("/api/nothing-here");

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.code()).isEqualTo("NOT_FOUND");
        assertThat(response.body().get("detail").asString()).isEqualTo("Not found");
    }

    @Test
    void protectedApiRequiresSignIn() {
        E2eClient.Response response = new E2eClient().get("/api/requests");

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.code()).isEqualTo("UNAUTHENTICATED");
        assertThat(response.header("Content-Type")).startsWith("application/problem+json");
    }
}
