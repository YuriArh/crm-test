package com.acme.dispatch.auth;

import jakarta.validation.constraints.NotBlank;

/** Body of {@code POST /api/auth/login}. */
public record LoginRequest(@NotBlank String username, @NotBlank String password) {

    @Override
    public String toString() {
        // Keep the password out of logs and debug output.
        return "LoginRequest[username=" + username + "]";
    }
}
