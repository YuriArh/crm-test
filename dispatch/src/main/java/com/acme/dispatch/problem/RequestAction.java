package com.acme.dispatch.problem;

/** An operator's action on a request that its current state allows. */
public enum RequestAction {
    /** Retry the delivery with the same operationId, as a new budget of retries. */
    RETRY
}
