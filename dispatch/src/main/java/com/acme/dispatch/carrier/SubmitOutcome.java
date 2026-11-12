package com.acme.dispatch.carrier;

/**
 * How a send attempt ended, from the sender's point of view. The names match {@code exchange_attempt.outcome}.
 */
public enum SubmitOutcome {
    /** {@code 202}: the carrier confirmed receipt (not execution). */
    ACKNOWLEDGED,
    /** The request did not reach the carrier or the carrier failed ({@code 5xx}): safe to retry. */
    TEMPORARY_ERROR,
    /** The request may have reached the carrier but no answer came (read timeout, broken connection). */
    UNKNOWN,
    /** The carrier rejected the request ({@code 4xx} other than a payload conflict): retrying will not help. */
    PERMANENT_ERROR,
    /** {@code 409 PAYLOAD_MISMATCH}: the operationId is already known to the carrier with other content. */
    PAYLOAD_CONFLICT
}
