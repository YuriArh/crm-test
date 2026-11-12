package com.acme.dispatch.exchange;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.acme.dispatch.carrier.CarrierClient;
import com.acme.dispatch.carrier.SubmitOutcome;
import com.acme.dispatch.carrier.SubmitResult;
import com.acme.dispatch.common.BusinessCalendar;
import com.acme.dispatch.config.IdGenerator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends queued operations to the carrier ({@code docs/dispatch-design.md} §5, D04, D-NF01).
 * <ol>
 *   <li>recover operations whose sender died mid-send ({@code SENDING} past its lease → {@code UNKNOWN});</li>
 *   <li>claim due operations in a short transaction ({@code FOR UPDATE SKIP LOCKED}, safe with several
 *       instances) and open an attempt row;</li>
 *   <li>call the carrier outside any transaction, with the operation's own immutable operationId;</li>
 *   <li>record the outcome with a conditional update fenced by the attempt number.</li>
 * </ol>
 * A resend after {@code UNKNOWN} is safe because the carrier executes one operationId at most once.
 */
@Component
public class SendWorker {

    private static final Logger log = LoggerFactory.getLogger(SendWorker.class);

    private final SendQueueDao queue;
    private final ExchangeAttemptDao attempts;
    private final CarrierClient carrier;
    private final BusinessCalendar calendar;
    private final IdGenerator ids;
    private final TransactionTemplate transaction;
    private final ExchangeProperties properties;
    private final RetryPolicy retryPolicy;

    public SendWorker(SendQueueDao queue, ExchangeAttemptDao attempts, CarrierClient carrier, BusinessCalendar calendar,
                      IdGenerator ids, TransactionTemplate transaction, ExchangeProperties properties) {
        this.queue = queue;
        this.attempts = attempts;
        this.carrier = carrier;
        this.calendar = calendar;
        this.ids = ids;
        this.transaction = transaction;
        this.properties = properties;
        this.retryPolicy = RetryPolicy.from(properties);
    }

    @Scheduled(fixedDelayString = "${app.exchange.send-interval-ms:1000}")
    void tick() {
        if (properties.enabled()) {
            runOnce();
        }
    }

    /** One pass: recovery, one claimed batch sent and recorded. Returns how many operations were sent. */
    public int runOnce() {
        recover();
        List<SendQueueDao.Claimed> batch = claim();
        for (SendQueueDao.Claimed claimed : batch) {
            send(claimed);
        }
        return batch.size();
    }

    private void recover() {
        transaction.executeWithoutResult(status -> {
            Instant now = calendar.now();
            List<UUID> recovered = queue.recoverExpired(now);
            if (!recovered.isEmpty()) {
                attempts.finishInterrupted(recovered, now);
                log.warn("Recovered {} operation(s) left in SENDING after an interrupted send", recovered.size());
            }
        });
    }

    private List<SendQueueDao.Claimed> claim() {
        return transaction.execute(status -> {
            Instant now = calendar.now();
            List<SendQueueDao.Claimed> claimed = queue.claimDue(now, now.plus(properties.lease()),
                    properties.sendBatchSize());
            for (SendQueueDao.Claimed operation : claimed) {
                attempts.start(ids.newId(), operation.operationId(), operation.attempt(), now);
            }
            return claimed;
        });
    }

    private void send(SendQueueDao.Claimed claimed) {
        Instant started = calendar.now();
        SubmitResult result;
        try {
            result = carrier.submit(claimed.request());
        } catch (RuntimeException e) {
            // Unexpected failure inside the client: we cannot tell whether the request left, so assume it might have.
            result = new SubmitResult(SubmitOutcome.UNKNOWN, null, "Client error: " + e);
        }
        SubmitResult outcome = result;
        transaction.executeWithoutResult(status -> record(claimed, outcome, started));
    }

    private void record(SendQueueDao.Claimed claimed, SubmitResult result, Instant started) {
        Instant now = calendar.now();
        attempts.finish(claimed.operationId(), claimed.attempt(), result, now, Duration.between(started, now));
        boolean updated = switch (result.outcome()) {
            case ACKNOWLEDGED -> queue.acknowledge(claimed, now);
            case TEMPORARY_ERROR -> retryOrFail(claimed, DeliveryStatus.RETRY_WAIT, result, now);
            case UNKNOWN -> retryOrFail(claimed, DeliveryStatus.UNKNOWN, result, now);
            case PERMANENT_ERROR, PAYLOAD_CONFLICT -> queue.fail(claimed, result.error(), now);
        };
        if (!updated) {
            // The claim is no longer ours: a result already confirmed delivery, or the lease expired and the
            // operation was recovered. The attempt is recorded; the newer state stays.
            log.debug("Operation {} changed while sending attempt {}; outcome {} recorded only in history",
                    claimed.operationId(), claimed.attempt(), result.outcome());
        } else if (result.outcome() != SubmitOutcome.ACKNOWLEDGED) {
            log.info("Operation {} attempt {}: {} ({})", claimed.operationId(), claimed.attempt(), result.outcome(),
                    result.error());
        }
    }

    private boolean retryOrFail(SendQueueDao.Claimed claimed, DeliveryStatus status, SubmitResult result, Instant now) {
        if (!retryPolicy.canRetry(claimed.attempt())) {
            return queue.fail(claimed, "Attempts exhausted (" + claimed.attempt() + "): " + result.error(), now);
        }
        Instant next = now.plus(retryPolicy.backoff(claimed.attempt()));
        return queue.scheduleRetry(claimed, status, next, result.error(), now);
    }
}
