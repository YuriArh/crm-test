package com.acme.carrier.web;

import org.springframework.http.HttpStatus;

/** Error codes of the contract ({@code Error.code} in carrier-api.yaml). */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    PAYLOAD_MISMATCH(HttpStatus.CONFLICT),
    OPERATION_NOT_FOUND(HttpStatus.NOT_FOUND),
    /** A demo scenario cannot change a result that is not settled yet ({@code admin.AdminScenariosController}). */
    OPERATION_NOT_SETTLED(HttpStatus.CONFLICT),
    /** Fault mode {@code unavailable} ({@code fault.UnavailableFilter}). */
    UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
