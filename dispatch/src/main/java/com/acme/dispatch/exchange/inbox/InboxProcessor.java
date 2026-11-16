package com.acme.dispatch.exchange.inbox;

import java.time.Instant;
import java.util.Optional;

import com.acme.dispatch.common.BusinessCalendar;
import com.acme.dispatch.exchange.ExchangeProperties;
import com.acme.dispatch.exchange.RetryPolicy;
import com.acme.dispatch.exchange.inbox.InboxDao.Decision;
import com.acme.dispatch.exchange.inbox.InboxDao.Pending;

import tools.jackson.databind.json.JsonMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The single handler of received carrier results ({@code docs/dispatch-design.md} §5,
 * {@code docs/specs/feed-bad-records.md} §4, D03, D04, D-NF02, D-NF03). Each due {@code RECEIVED} record is
 * processed in its own transaction, so a record that cannot be processed affects nobody else:
 * <ul>
 *   <li>without a clean message id, malformed, or for an unknown operation — {@code INVALID};</li>
 *   <li>otherwise decided by {@link ResultRules} under a lock on the operation row: {@code APPLIED},
 *       {@code STALE} or {@code CONFLICT}.</li>
 * </ul>
 * The work runs under a savepoint inside the record's transaction. If it fails, it is rolled back and the outcome is
 * recorded in the same transaction: data the database refused — {@code INVALID} at once; anything else is
 * temporary — retried after a growing pause, {@code FAILED} after the last attempt. If even that cannot be recorded
 * (the database is gone), the attempt is not counted and the pass stops.
 */
@Component
public class InboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(InboxProcessor.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final InboxDao inbox;
    private final BusinessCalendar calendar;
    private final TransactionTemplate transaction;
    private final TransactionTemplate savepoint;
    private final RetryPolicy retryPolicy;

    public InboxProcessor(InboxDao inbox, BusinessCalendar calendar, PlatformTransactionManager transactionManager,
                          ExchangeProperties properties) {
        this.inbox = inbox;
        this.calendar = calendar;
        this.transaction = new TransactionTemplate(transactionManager);
        this.savepoint = new TransactionTemplate(transactionManager);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        this.retryPolicy = RetryPolicy.forInboxProcessing(properties);
    }

    /** Processes every record that is due now. Returns how many records were processed (decided or rescheduled). */
    public int processDue() {
        int processed = 0;
        while (true) {
            Boolean handled;
            try {
                handled = transaction.execute(status -> processNext());
            } catch (RuntimeException e) {
                log.error("Processing of received results stopped; the record stays due and is retried on the next "
                        + "pass: {}", e.toString());
                return processed;
            }
            if (!Boolean.TRUE.equals(handled)) {
                return processed;
            }
            processed++;
        }
    }

    private boolean processNext() {
        Instant now = calendar.now();
        Optional<Pending> claimed = inbox.claimNextDue(now);
        if (claimed.isEmpty()) {
            return false;
        }
        Pending record = claimed.get();
        ResultMessage message = null;
        try {
            ResultMessage parsed = ResultMessage.parse(JSON.readTree(record.payload()));
            message = parsed;
            savepoint.executeWithoutResult(status -> decide(record, parsed, now));
        } catch (DataIntegrityViolationException e) {
            String reason = SafeText.of("Rejected by the database: " + rootMessage(e));
            inbox.recordOutcome(record.id(), Decision.INVALID, reason, message);
            log.warn("Result {} for operation {} is INVALID: {}", record.messageId(), operationId(message), reason);
        } catch (RuntimeException e) {
            retryOrFail(record, message, e, now);
        }
        return true;
    }

    private void decide(Pending record, ResultMessage message, Instant now) {
        if (record.messageId() == null) {
            invalid(record, message, "messageId is missing or malformed");
            return;
        }
        if (!message.isValid()) {
            invalid(record, message, message.invalidReason());
            return;
        }
        Optional<InboxDao.Target> target = inbox.lockTarget(message.operationId());
        if (target.isEmpty()) {
            invalid(record, message, "Unknown operationId " + message.operationId());
            return;
        }
        String appliedSameSeq = inbox.appliedContent(message.operationId(), message.sequence()).orElse(null);
        switch (ResultRules.decide(target.get().lastAppliedSequence(), appliedSameSeq, target.get().isFinal(),
                message)) {
            case APPLIED -> {
                inbox.applyToRequest(target.get().requestId(), message, now);
                inbox.applyToOperation(message.operationId(), message.sequence(), now);
                inbox.recordOutcome(record.id(), Decision.APPLIED, null, message);
            }
            case STALE -> inbox.recordOutcome(record.id(), Decision.STALE, "Sequence " + message.sequence()
                    + " is not newer than applied " + target.get().lastAppliedSequence(), message);
            case CONFLICT -> {
                String reason = conflictReason(target.get(), message);
                inbox.recordOutcome(record.id(), Decision.CONFLICT, reason, message);
                log.warn("Result {} for operation {} is CONFLICT: {}", record.messageId(), message.operationId(),
                        reason);
            }
        }
    }

    private void invalid(Pending record, ResultMessage message, String reason) {
        String safeReason = SafeText.of(reason);
        inbox.recordOutcome(record.id(), Decision.INVALID, safeReason, message);
        log.warn("Result {} for operation {} is INVALID: {}", record.messageId(), operationId(message), safeReason);
    }

    private void retryOrFail(Pending record, ResultMessage message, RuntimeException e, Instant now) {
        int attempts = record.attempts() + 1;
        String error = SafeText.of(e.toString());
        if (retryPolicy.canRetry(attempts)) {
            Instant next = now.plus(retryPolicy.backoff(attempts));
            inbox.recordRetry(record.id(), attempts, next, error);
            log.warn("Result {} for operation {}: processing attempt {} failed, next at {}: {}", record.messageId(),
                    operationId(message), attempts, next, error);
        } else {
            String reason = SafeText.of("Processing failed " + attempts + " times: " + e);
            inbox.recordFailed(record.id(), attempts, reason, error);
            log.error("Result {} for operation {} is FAILED: {}", record.messageId(), operationId(message), reason);
        }
    }

    private static String conflictReason(InboxDao.Target target, ResultMessage message) {
        if (target.isFinal() && message.sequence() > target.lastAppliedSequence()) {
            return "Request already has a final result " + target.businessStatus() + "; a final result is not replaced";
        }
        return "Sequence " + message.sequence() + " was already applied with different content";
    }

    private static Object operationId(ResultMessage message) {
        return message == null ? null : message.operationId();
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}
