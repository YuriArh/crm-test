package com.acme.dispatch.exchange.inbox;

import java.time.Instant;
import java.util.UUID;

import tools.jackson.databind.json.JsonMapper;

import org.springframework.stereotype.Component;

/**
 * Applies a record an operator chose in a result conflict ({@code docs/specs/operator-problems.md} §2.4): the only
 * way a final result changes once it is final. Runs in the caller's transaction and locks the operation row first,
 * as the result handler does, so it never races with processing.
 */
@Component
public class OperatorResults {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final InboxDao inbox;

    public OperatorResults(InboxDao inbox) {
        this.inbox = inbox;
    }

    /**
     * Writes the record's final result to its request ({@code version + 1}) and makes it the applied record, so a
     * repeat of it is stale and not a new conflict.
     *
     * @throws IllegalStateException if the record is not a valid final result of a known operation (the caller
     *                               offers the decision only for such records)
     */
    public void applyChosen(UUID inboxId, Instant now) {
        ResultMessage message = ResultMessage.parse(JSON.readTree(inbox.payloadOf(inboxId)));
        if (!message.isValid() || !message.isFinal()) {
            throw new IllegalStateException("Record " + inboxId + " is not a final result: " + message.status());
        }
        InboxDao.Target target = inbox.lockTarget(message.operationId())
                .orElseThrow(() -> new IllegalStateException("Record " + inboxId + " names an unknown operation"));
        inbox.applyToRequest(target.requestId(), message, now);
        inbox.makeApplied(message.operationId(), message.sequence(), inboxId, now);
    }
}
