package com.acme.dispatch.carrier;

import tools.jackson.databind.JsonNode;

/**
 * The carrier's answer to {@code GET /operations/{operationId}} ({@code docs/specs/reconciliation.md} §2.2). The
 * envelope ({@code OperationState}) is checked here; the result inside it is checked by the result handler's strict
 * rules, like any other result. Every variant keeps the carrier's body verbatim.
 */
public sealed interface LookupResult {

    /** The carrier's body as it came. */
    String raw();

    /**
     * The carrier knows the operation.
     *
     * @param state  {@code ACCEPTED} or {@code EXECUTED}
     * @param result the operation's result as it came, or {@code null} while it has none
     */
    record Found(String state, JsonNode result, String raw) implements LookupResult {
    }

    /** {@code 404}: the carrier does not know the operation; code and message from its error body, if readable. */
    record NotFound(String code, String message, String raw) implements LookupResult {
    }

    /** Any other answer, or a body against the contract; code and message from an error body, if there is one. */
    record Invalid(String reason, String code, String message, String raw) implements LookupResult {
    }
}
