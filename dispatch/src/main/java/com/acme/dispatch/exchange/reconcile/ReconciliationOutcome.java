package com.acme.dispatch.exchange.reconcile;

/** How a check compared our state with the carrier's ({@code docs/specs/reconciliation.md} §2.4). */
public enum ReconciliationOutcome {
    /** Nothing to fix. */
    MATCH,
    /** The carrier has a result and we have none: take it over through the result handler. */
    EXTERNAL_AHEAD,
    /** The carrier does not know an operation we may have delivered. */
    EXTERNAL_MISSING,
    /** The results differ, or only we have one. */
    CONFLICT,
    /** The carrier did not answer, or answered against the contract. */
    ERROR
}
