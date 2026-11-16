package com.acme.dispatch.exchange.inbox;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.acme.dispatch.common.BusinessCalendar;
import com.acme.dispatch.exchange.DeliveryStatus;
import com.acme.dispatch.exchange.ExchangeProperties;
import com.acme.dispatch.exchange.RetryPolicy;
import com.acme.dispatch.exchange.inbox.InboxDao.Decision;
import com.acme.dispatch.exchange.inbox.InboxDao.Pending;
import com.acme.dispatch.problem.ProblemService;

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
 * Only the carrier's data is written under a savepoint inside the record's transaction. If that fails, it is rolled
 * back and the outcome is recorded in the same transaction: data the database refused — {@code INVALID} at once;
 * anything else is temporary — retried after a growing pause, {@code FAILED} after the last attempt. The outcome and
 * the problems it opens or closes ({@code docs/specs/operator-problems.md} §2.3) are written after the savepoint, so
 * a failure of ours is never blamed on the carrier. If they cannot be written (the database is gone, a bug), the
 * attempt is not counted and the pass stops.
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
    private final ProblemService problems;

    public InboxProcessor(InboxDao inbox, BusinessCalendar calendar, PlatformTransactionManager transactionManager,
                          ExchangeProperties properties, ProblemService problems) {
        this.inbox = inbox;
        this.calendar = calendar;
        this.transaction = new TransactionTemplate(transactionManager);
        this.savepoint = new TransactionTemplate(transactionManager);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        this.retryPolicy = RetryPolicy.forInboxProcessing(properties);
        this.problems = problems;
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
        Optional<Outcome> outcome;
        try {
            ResultMessage parsed = ResultMessage.parse(JSON.readTree(record.payload()));
            message = parsed;
            outcome = Optional.of(savepoint.execute(status -> decide(record, parsed, now)));
        } catch (DataIntegrityViolationException e) {
            outcome = Optional.of(invalid(record, message, "Rejected by the database: " + rootMessage(e)));
        } catch (RuntimeException e) {
            outcome = retryOrFail(record, message, e, now);
        }
        outcome.ifPresent(decided -> finish(record, decided, now));
        return true;
    }

    /**
     * Under the savepoint: checks the record and applies it. Writes nothing but the carrier's data, so a refusal of
     * the database here is a refusal of that data.
     */
    private Outcome decide(Pending record, ResultMessage message, Instant now) {
        if (record.messageId() == null) {
            return invalid(record, message, "messageId is missing or malformed");
        }
        if (!message.isValid()) {
            return invalid(record, message, message.invalidReason());
        }
        Optional<InboxDao.Target> target = inbox.lockTarget(message.operationId());
        if (target.isEmpty()) {
            return invalid(record, message, "Unknown operationId " + message.operationId());
        }
        UUID requestId = target.get().requestId();
        String appliedSameSeq = inbox.appliedContent(message.operationId(), message.sequence()).orElse(null);
        return switch (ResultRules.decide(target.get().lastAppliedSequence(), appliedSameSeq, target.get().isFinal(),
                message)) {
            case APPLIED -> {
                inbox.applyToRequest(requestId, message, now);
                boolean acknowledged = inbox.applyToOperation(message.operationId(), message.sequence(), record.id(),
                        now);
                yield new Outcome(Decision.APPLIED, null, message, requestId, acknowledged);
            }
            case STALE -> new Outcome(Decision.STALE, "Sequence " + message.sequence()
                    + " is not newer than applied " + target.get().lastAppliedSequence(), message, requestId, false);
            case CONFLICT -> {
                String reason = conflictReason(target.get(), message);
                log.warn("Result {} for operation {} is CONFLICT: {}", record.messageId(), message.operationId(),
                        reason);
                yield new Outcome(Decision.CONFLICT, reason, message, requestId, false);
            }
        };
    }

    /** After the savepoint: records the outcome and opens or closes the problems it causes. */
    private void finish(Pending record, Outcome outcome, Instant now) {
        if (outcome.decision() != Decision.FAILED) {
            inbox.recordOutcome(record.id(), outcome.decision(), outcome.reason(), outcome.message());
        }
        problems.onRecordOutcome(record.id(), outcome.requestId(), outcome.decision(), outcome.reason(), now);
        if (outcome.acknowledged()) {
            problems.onDeliveryStatus(outcome.requestId(), outcome.message().operationId(),
                    DeliveryStatus.ACKNOWLEDGED, null, null, now);
        }
    }

    /** An invalid record; its request is found by the operationId if that could be read (else none). */
    private Outcome invalid(Pending record, ResultMessage message, String reason) {
        String safeReason = SafeText.of(reason);
        log.warn("Result {} for operation {} is INVALID: {}", record.messageId(), operationId(message), safeReason);
        UUID requestId = message == null || message.operationId() == null
                ? null
                : inbox.requestOf(message.operationId()).orElse(null);
        return new Outcome(Decision.INVALID, safeReason, message, requestId, false);
    }

    /** A temporary failure: retried later (nothing to finish), or {@code FAILED} after the last attempt. */
    private Optional<Outcome> retryOrFail(Pending record, ResultMessage message, RuntimeException e, Instant now) {
        int attempts = record.attempts() + 1;
        String error = SafeText.of(e.toString());
        if (retryPolicy.canRetry(attempts)) {
            Instant next = now.plus(retryPolicy.backoff(attempts));
            inbox.recordRetry(record.id(), attempts, next, error);
            log.warn("Result {} for operation {}: processing attempt {} failed, next at {}: {}", record.messageId(),
                    operationId(message), attempts, next, error);
            return Optional.empty();
        }
        String reason = SafeText.of("Processing failed " + attempts + " times: " + e);
        inbox.recordFailed(record.id(), attempts, reason, error);
        log.error("Result {} for operation {} is FAILED: {}", record.messageId(), operationId(message), reason);
        UUID requestId = message == null || message.operationId() == null
                ? null
                : inbox.requestOf(message.operationId()).orElse(null);
        return Optional.of(new Outcome(Decision.FAILED, reason, message, requestId, false));
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

    /**
     * How a record was decided.
     *
     * @param requestId    its request, if known
     * @param acknowledged whether applying it confirmed the delivery
     */
    private record Outcome(Decision decision, String reason, ResultMessage message, UUID requestId,
                           boolean acknowledged) {
    }
}
