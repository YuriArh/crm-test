package com.acme.dispatch.problem;

/** What needs an operator ({@code docs/specs/operator-problems.md} §2.1). */
public enum ProblemKind {
    /** Delivery is UNKNOWN: whether the carrier got the request is not known. Closed by confirmation only. */
    DELIVERY_UNKNOWN,
    /** Delivery FAILED: attempts exhausted or refused by the carrier. */
    DELIVERY_FAILED,
    /** A received record was INVALID: bad data from the carrier. */
    INVALID_MESSAGE,
    /** A received record FAILED: we could not process it. */
    MESSAGE_FAILED,
    /** A received record was a CONFLICT with the applied result. */
    RESULT_CONFLICT,
    /** Reconciliation: the carrier does not know an operation it acknowledged ({@code reconciliation.md} §2.5). */
    EXTERNAL_MISSING,
    /** Reconciliation: our result differs from the carrier's, or only we have one. */
    RECONCILIATION_CONFLICT
}
