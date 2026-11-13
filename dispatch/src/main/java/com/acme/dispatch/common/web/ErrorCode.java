package com.acme.dispatch.common.web;

import org.springframework.http.HttpStatus;

/** Machine-readable error codes, returned as the {@code code} property of Problem Details. */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),
    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "Idempotency-Key header is required"),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_CONTENT, "Idempotency-Key was already used with a different request"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required"),
    BAD_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Wrong username or password"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Access denied"),
    /** Missing or wrong CSRF token: the client may fetch a fresh token and repeat the call. */
    CSRF_INVALID(HttpStatus.FORBIDDEN, "Missing or invalid CSRF token"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Not found"),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "The object was changed by someone else"),
    INVALID_STATE(HttpStatus.CONFLICT, "Action is not allowed in the current state"),
    INVALID_ASSIGNEE(HttpStatus.UNPROCESSABLE_CONTENT, "Assignee must be an operator of the same service"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }
}
