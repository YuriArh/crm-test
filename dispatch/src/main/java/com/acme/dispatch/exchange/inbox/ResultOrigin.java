package com.acme.dispatch.exchange.inbox;

import java.util.UUID;

/**
 * Where an applied result came from, for the request's history: always a carrier record; an operator and their
 * problem when the operator chose it.
 */
public record ResultOrigin(UUID inboxId, UUID operatorId, UUID problemId) {

    /** A record applied by processing (feed or reconciliation). */
    public static ResultOrigin record(UUID inboxId) {
        return new ResultOrigin(inboxId, null, null);
    }

    /** A record an operator chose in a problem. */
    public static ResultOrigin chosen(UUID inboxId, UUID operatorId, UUID problemId) {
        return new ResultOrigin(inboxId, operatorId, problemId);
    }
}
