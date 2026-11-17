package com.acme.dispatch.exchange.inbox;

import java.time.Instant;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
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
    private final ResultInbox resultInbox;

    public OperatorResults(InboxDao inbox, ResultInbox resultInbox) {
        this.inbox = inbox;
        this.resultInbox = resultInbox;
    }

    /**
     * Writes the record's final result to its request ({@code version + 1}) and makes it the applied record, so a
     * repeat of it is stale and not a new conflict.
     *
     * @throws IllegalStateException if the record is not a valid final result of a known operation (the caller
     *                               offers the decision only for such records)
     */
    /**
     * Applies the carrier's final result an operator chose in a reconciliation conflict ({@code reconciliation.md}
     * §2.7). The applied content always lies in {@code inbox}, so the result becomes a record of reconciliation — the
     * existing one of the same message, if there is one — applied like {@link #applyChosen} and marked
     * {@code APPLIED} with the decision as its reason. A record is locked before the operation, in the result
     * handler's order.
     *
     * @param carrierAnswer the carrier's answer the problem shows ({@code OperationState})
     * @return {@code false} if the answer has no valid final result: there is nothing to apply
     */
    public boolean applyCarrierAnswer(String carrierAnswer, String reason, Instant now) {
        JsonNode result = readResult(carrierAnswer);
        if (result == null) {
            return false;
        }
        ResultMessage message = ResultMessage.parse(result);
        if (!message.isValid() || !message.isFinal()) {
            return false;
        }
        UUID inboxId = resultInbox.receive(result, InboxSource.RECONCILIATION)
                .orElseGet(() -> inbox.findId(InboxSource.RECONCILIATION, message.messageId()).orElseThrow());
        inbox.lockRecord(inboxId);
        applyChosen(inboxId, now);
        inbox.recordOutcome(inboxId, InboxDao.Decision.APPLIED, reason, message, now);
        return true;
    }

    private static JsonNode readResult(String carrierAnswer) {
        try {
            JsonNode result = JSON.readTree(carrierAnswer).get("result");
            return result == null || !result.isObject() ? null : result;
        } catch (RuntimeException e) {
            return null;
        }
    }

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
