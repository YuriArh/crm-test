package com.acme.dispatch.request;

/** Business result of a delivery request, reported by the carrier. */
public enum BusinessStatus {
    /** No result yet. */
    AWAITING,
    /** The carrier has taken the request into work. */
    IN_PROGRESS,
    /** Final: shipment number and price received. */
    SUCCEEDED,
    /** Final: the carrier rejected the request. */
    REJECTED;

    public boolean isFinal() {
        return this == SUCCEEDED || this == REJECTED;
    }
}
