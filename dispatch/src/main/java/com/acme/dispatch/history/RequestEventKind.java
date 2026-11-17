package com.acme.dispatch.history;

/** What a request event records ({@code docs/specs/request-history.md} §2.2). */
public enum RequestEventKind {
    /** The comment or the assignee changed; a new version, by a user. */
    EDITED,
    /** The result changed; a new version, from a carrier record (by an operator if they chose it). */
    RESULT_CHANGED,
    /** An operator retried the delivery; the request itself does not change. */
    DELIVERY_RETRIED
}
