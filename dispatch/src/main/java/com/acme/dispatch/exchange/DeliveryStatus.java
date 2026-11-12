package com.acme.dispatch.exchange;

/** Whether the request reached the carrier. Receipt is not execution (D01). */
public enum DeliveryStatus {
    /** Created, waiting to be sent. */
    PENDING,
    /** A worker is sending it right now (holds a lease). */
    SENDING,
    /** A temporary error happened; waiting for the next attempt. */
    RETRY_WAIT,
    /** Timeout or broken connection after sending: unknown whether the carrier got it. */
    UNKNOWN,
    /** The carrier confirmed receipt. */
    ACKNOWLEDGED,
    /** Permanent error or attempts exhausted; an operator decides. */
    FAILED
}
