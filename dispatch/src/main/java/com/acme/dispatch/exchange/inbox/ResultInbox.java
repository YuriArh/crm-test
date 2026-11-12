package com.acme.dispatch.exchange.inbox;

import java.time.Instant;
import java.util.Optional;

import com.acme.dispatch.common.BusinessCalendar;
import com.acme.dispatch.config.IdGenerator;

import tools.jackson.databind.JsonNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single handler of carrier results, whatever transport delivered them ({@code docs/dispatch-design.md} §5,
 * D03, D-NF02, D-NF03). Every message is recorded in {@code inbox} with how it was processed:
 * <ul>
 *   <li>repeated {@code messageId} — no new row, {@code duplicate_count + 1}, no effect;</li>
 *   <li>malformed or for an unknown operation — {@code INVALID}, kept for review;</li>
 *   <li>otherwise decided by {@link ResultRules} under a lock on the operation row: {@code APPLIED},
 *       {@code STALE} or {@code CONFLICT}.</li>
 * </ul>
 */
@Service
public class ResultInbox {

    private static final Logger log = LoggerFactory.getLogger(ResultInbox.class);

    private final InboxDao inbox;
    private final BusinessCalendar calendar;
    private final IdGenerator ids;

    public ResultInbox(InboxDao inbox, BusinessCalendar calendar, IdGenerator ids) {
        this.inbox = inbox;
        this.calendar = calendar;
        this.ids = ids;
    }

    /** Must run inside the caller's transaction, together with moving the feed cursor. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome accept(JsonNode raw, InboxSource source) {
        ResultMessage message = ResultMessage.parse(raw);
        Instant now = calendar.now();

        if (message.messageId() != null && inbox.countDuplicate(message.messageId(), now)) {
            return Outcome.DUPLICATE;
        }
        if (!message.isValid()) {
            return store(message, source, Outcome.INVALID, message.invalidReason(), now);
        }
        Optional<InboxDao.Target> target = inbox.lockTarget(message.operationId());
        if (target.isEmpty()) {
            return store(message, source, Outcome.INVALID, "Unknown operationId " + message.operationId(), now);
        }
        String appliedSameSeq = inbox.appliedContent(message.operationId(), message.sequence()).orElse(null);
        ResultRules.Decision decision = ResultRules.decide(target.get().lastAppliedSequence(), appliedSameSeq,
                target.get().isFinal(), message);
        return switch (decision) {
            case APPLIED -> {
                Outcome stored = store(message, source, Outcome.APPLIED, null, now);
                if (stored == Outcome.APPLIED) {
                    inbox.applyToRequest(target.get().requestId(), message, now);
                    inbox.applyToOperation(message.operationId(), message.sequence(), now);
                }
                yield stored;
            }
            case STALE -> store(message, source, Outcome.STALE,
                    "Sequence " + message.sequence() + " is not newer than applied " + target.get().lastAppliedSequence(),
                    now);
            case CONFLICT -> store(message, source, Outcome.CONFLICT, conflictReason(target.get(), message), now);
        };
    }

    private Outcome store(ResultMessage message, InboxSource source, Outcome outcome, String reason, Instant now) {
        if (!inbox.insert(ids.newId(), message, source, outcome.name(), reason, now)) {
            inbox.countDuplicate(message.messageId(), now);
            return Outcome.DUPLICATE;
        }
        if (outcome == Outcome.INVALID || outcome == Outcome.CONFLICT) {
            log.warn("Result {} for operation {} is {}: {}", message.messageId(), message.operationId(), outcome, reason);
        }
        return outcome;
    }

    private static String conflictReason(InboxDao.Target target, ResultMessage message) {
        if (target.isFinal() && message.sequence() > target.lastAppliedSequence()) {
            return "Request already has a final result " + target.businessStatus() + "; a final result is not replaced";
        }
        return "Sequence " + message.sequence() + " was already applied with different content";
    }

    /** How a message was processed; the first four are also stored as {@code inbox.processing_status}. */
    public enum Outcome {
        APPLIED,
        STALE,
        CONFLICT,
        INVALID,
        DUPLICATE
    }
}
