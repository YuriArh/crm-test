package com.acme.dispatch.exchange;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.acme.dispatch.carrier.CarrierClient;
import com.acme.dispatch.carrier.SubmitOutcome;
import com.acme.dispatch.carrier.SubmitResult;
import com.acme.dispatch.common.BusinessCalendar;
import com.acme.dispatch.config.IdGenerator;
import com.acme.dispatch.problem.ProblemService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends queued operations to the carrier ({@code docs/dispatch-design.md} §5, D04, D-NF01).
 * <ol>
 *   <li>recover operations whose sender died mid-send ({@code SENDING} past its lease): an attempt with an unknown
 *       outcome under the same retry policy, so a paused {@code UNKNOWN} or {@code FAILED}; close attempts left
 *       open past the lease;</li>
 *   <li>claim the next due operation right before sending it, in a short transaction ({@code FOR UPDATE SKIP
 *       LOCKED}, safe with several instances), and open its attempt row — one at a time, so the lease always
 *       measures the call in progress;</li>
 *   <li>call the carrier outside any transaction, with the operation's own immutable operationId;</li>
 *   <li>record the outcome with a conditional update fenced by the attempt number.</li>
 * </ol>
 * A resend after {@code UNKNOWN} is safe because the carrier executes one operationId at most once.
 */
@Component
public class SendWorker {

    private static final Logger log = LoggerFactory.getLogger(SendWorker.class);
    /** Recovered operationIds written to one log line. */
    private static final int LOGGED_IDS = 10;
    /** The outcome of a send the worker did not live to record. */
    static final String INTERRUPTED = "Send was interrupted (lease expired)";

    private final SendQueueDao queue;
    private final ExchangeAttemptDao attempts;
    private final CarrierClient carrier;
    private final BusinessCalendar calendar;
    private final IdGenerator ids;
    private final TransactionTemplate transaction;
    private final ExchangeProperties properties;
    private final RetryPolicy retryPolicy;
    private final ProblemService problems;

    public SendWorker(SendQueueDao queue, ExchangeAttemptDao attempts, CarrierClient carrier, BusinessCalendar calendar,
                      IdGenerator ids, TransactionTemplate transaction, ExchangeProperties properties,
                      ProblemService problems) {
        this.queue = queue;
        this.attempts = attempts;
        this.carrier = carrier;
        this.calendar = calendar;
        this.ids = ids;
        this.transaction = transaction;
        this.properties = properties;
        this.retryPolicy = RetryPolicy.forSending(properties);
        this.problems = problems;
    }

    @Scheduled(fixedDelayString = "${app.exchange.send-interval-ms:1000}")
    void tick() {
        if (properties.enabled()) {
            runOnce();
        }
    }

    /**
     * One pass: recovery, then up to {@code sendsPerPass} operations, each claimed right before it is sent and
     * recorded. Stops early when nothing is due. Returns how many operations were sent.
     */
    public int runOnce() {
        recover();
        int sent = 0;
        while (sent < properties.sendsPerPass()) {
            Optional<SendQueueDao.Claimed> claimed = claimNext();
            if (claimed.isEmpty()) {
                break;
            }
            send(claimed.get());
            sent++;
        }
        return sent;
    }

    /**
     * An operation left in {@code SENDING} past its lease is an attempt with an unknown outcome: the same retry
     * policy as for a timeout decides between a paused retry and {@code FAILED}; the attempt was counted at claim.
     * The policy counts attempts within the current budget ({@code attempts - retry_from}): an operator's retry
     * starts a new one without resetting the attempt number.
     */
    private void recover() {
        transaction.executeWithoutResult(status -> {
            Instant now = calendar.now();
            List<UUID> rescheduled = new ArrayList<>();
            List<UUID> failed = new ArrayList<>();
            for (SendQueueDao.Expired expired : queue.findExpired(now)) {
                int inBudget = expired.attemptInBudget();
                if (retryPolicy.canRetry(inBudget)) {
                    Instant next = now.plus(retryPolicy.backoff(inBudget));
                    if (queue.rescheduleInterrupted(expired, next, INTERRUPTED, now)) {
                        rescheduled.add(expired.operationId());
                        problems.onDeliveryStatus(expired.requestId(), expired.operationId(), DeliveryStatus.UNKNOWN,
                                INTERRUPTED, INTERRUPTED, now);
                    }
                } else {
                    String error = "Attempts exhausted (" + inBudget + "): " + INTERRUPTED;
                    if (queue.failInterrupted(expired, error, now)) {
                        failed.add(expired.operationId());
                        problems.onDeliveryStatus(expired.requestId(), expired.operationId(), DeliveryStatus.FAILED,
                                error, INTERRUPTED, now);
                    }
                }
            }
            if (!rescheduled.isEmpty()) {
                log.warn("Rescheduled {} operation(s) after an interrupted send: {}", rescheduled.size(),
                        logged(rescheduled));
            }
            if (!failed.isEmpty()) {
                log.warn("Failed {} operation(s) after an interrupted send, attempts exhausted: {}", failed.size(),
                        logged(failed));
            }
            int closed = attempts.finishExpired(now, now.minus(properties.lease()));
            if (closed > 0) {
                log.warn("Closed {} exchange attempt(s) left open past the lease", closed);
            }
        });
    }

    /** After a long outage there may be many: the first {@link #LOGGED_IDS}, then an ellipsis. */
    private static String logged(List<UUID> operationIds) {
        return operationIds.subList(0, Math.min(operationIds.size(), LOGGED_IDS))
                + (operationIds.size() > LOGGED_IDS ? " …" : "");
    }

    /** Claims one due operation and opens its attempt in one short transaction, right before the call. */
    private Optional<SendQueueDao.Claimed> claimNext() {
        return transaction.execute(status -> {
            Instant now = calendar.now();
            Optional<SendQueueDao.Claimed> claimed = queue.claimNext(now, now.plus(properties.lease()));
            claimed.ifPresent(operation ->
                    attempts.start(ids.newId(), operation.operationId(), operation.attempt(), operation.claimedAt()));
            return claimed;
        });
    }

    private void send(SendQueueDao.Claimed claimed) {
        SubmitResult result;
        try {
            result = carrier.submit(claimed.request());
        } catch (RuntimeException e) {
            // Unexpected failure inside the client: we cannot tell whether the request left, so assume it might have.
            result = new SubmitResult(SubmitOutcome.UNKNOWN, null, "Client error: " + e);
        }
        SubmitResult outcome = result;
        transaction.executeWithoutResult(status -> record(claimed, outcome));
    }

    private void record(SendQueueDao.Claimed claimed, SubmitResult result) {
        Instant now = calendar.now();
        // Measured from the instant stored as started_at, so started_at + duration = finished_at.
        attempts.finish(claimed.operationId(), claimed.attempt(), result, now,
                Duration.between(claimed.claimedAt(), now));
        Optional<Transition> transition = switch (result.outcome()) {
            case ACKNOWLEDGED -> queue.acknowledge(claimed, now)
                    ? Optional.of(new Transition(DeliveryStatus.ACKNOWLEDGED, null))
                    : Optional.empty();
            case TEMPORARY_ERROR -> retryOrFail(claimed, DeliveryStatus.RETRY_WAIT, result, now);
            case UNKNOWN -> retryOrFail(claimed, DeliveryStatus.UNKNOWN, result, now);
            case PERMANENT_ERROR, PAYLOAD_CONFLICT -> queue.fail(claimed, result.error(), now)
                    ? Optional.of(new Transition(DeliveryStatus.FAILED, result.error()))
                    : Optional.empty();
        };
        if (transition.isEmpty()) {
            // The claim is no longer ours: a result already confirmed delivery, or the lease expired and the
            // operation was recovered. The attempt is recorded; the newer state stays.
            log.debug("Operation {} changed while sending attempt {}; outcome {} recorded only in history",
                    claimed.operationId(), claimed.attempt(), result.outcome());
            return;
        }
        problems.onDeliveryStatus(claimed.requestId(), claimed.operationId(), transition.get().status(),
                transition.get().error(), result.outcome() == SubmitOutcome.UNKNOWN ? result.error() : null, now);
        if (result.outcome() != SubmitOutcome.ACKNOWLEDGED) {
            log.info("Operation {} attempt {}: {} ({})", claimed.operationId(), claimed.attempt(), result.outcome(),
                    result.error());
        }
    }

    private Optional<Transition> retryOrFail(SendQueueDao.Claimed claimed, DeliveryStatus status, SubmitResult result,
                                             Instant now) {
        int inBudget = claimed.attemptInBudget();
        if (!retryPolicy.canRetry(inBudget)) {
            String error = "Attempts exhausted (" + inBudget + "): " + result.error();
            return queue.fail(claimed, error, now)
                    ? Optional.of(new Transition(DeliveryStatus.FAILED, error))
                    : Optional.empty();
        }
        Instant next = now.plus(retryPolicy.backoff(inBudget));
        return queue.scheduleRetry(claimed, status, next, result.error(), now)
                ? Optional.of(new Transition(status, result.error()))
                : Optional.empty();
    }

    /** A transition that took effect: the new status and the operation's new {@code last_error}. */
    private record Transition(DeliveryStatus status, String error) {
    }
}
