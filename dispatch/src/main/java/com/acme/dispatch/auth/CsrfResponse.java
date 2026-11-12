package com.acme.dispatch.auth;

/** Body of {@code GET /api/auth/csrf}: send {@code token} in the header {@code headerName}. */
public record CsrfResponse(String headerName, String token) {
}
