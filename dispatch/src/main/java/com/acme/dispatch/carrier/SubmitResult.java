package com.acme.dispatch.carrier;

/**
 * Result of one send attempt.
 *
 * @param httpStatus {@code null} when no response was received
 * @param error      short description for {@code last_error}; {@code null} on success
 */
public record SubmitResult(SubmitOutcome outcome, Integer httpStatus, String error) {

    static SubmitResult of(SubmitOutcome outcome, Integer httpStatus, String error) {
        return new SubmitResult(outcome, httpStatus, error);
    }
}
