package com.acme.dispatch.exchange.reconcile;

/** What started a check ({@code docs/specs/reconciliation.md} §2.1). */
public enum RunType {
    /** The pass right after the application started. */
    STARTUP,
    /** A background pass. */
    SCHEDULED,
    /** An operator's «Сверить сейчас». */
    MANUAL
}
