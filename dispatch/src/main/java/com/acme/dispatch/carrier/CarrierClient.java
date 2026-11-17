package com.acme.dispatch.carrier;

import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/** The carrier as seen by dispatch: the only place that knows its HTTP API. */
public interface CarrierClient {

    /** Sends an operation. Never throws for exchange failures: they are classified in the result. */
    SubmitResult submit(SubmitRequest request);

    /**
     * Reads the result feed after {@code after}. Entries are returned as raw JSON: validating them is the job of
     * the result handler, so one malformed entry does not block the whole feed.
     *
     * @throws CarrierUnavailableException if the feed cannot be read now
     */
    List<JsonNode> results(long after, int limit);

    /**
     * Asks the carrier what it knows about an operation (reconciliation).
     *
     * @throws CarrierUnavailableException if the carrier cannot answer now (no connection, timeout, 5xx)
     */
    LookupResult lookup(UUID operationId);
}
