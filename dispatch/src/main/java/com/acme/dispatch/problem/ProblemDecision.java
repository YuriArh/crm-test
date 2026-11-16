package com.acme.dispatch.problem;

/** How a problem was closed. */
public enum ProblemDecision {
    /** The system closed it: the delivery was confirmed. */
    SYSTEM,
    /** An operator retried the delivery. */
    RETRIED,
    /** An operator applied the carrier's disputed result. */
    APPLY_CARRIER,
    /** An operator kept the applied result. */
    KEEP_OURS,
    /** An operator looked into a record and closed it. */
    ACKNOWLEDGED
}
