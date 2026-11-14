package com.acme.carrier.execution;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.acme.carrier.config.CarrierProperties;
import com.acme.carrier.config.UuidV7Generator;
import com.acme.carrier.operation.OperationDao;
import com.acme.carrier.operation.ResultStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Executes accepted operations in the background and publishes their results to the feed:
 * {@code IN_PROGRESS} (sequence 1), then the final {@code SUCCEEDED}/{@code REJECTED} (sequence 2).
 * An operation that remembered {@code delayed} is executed at once but its entries are published later by
 * {@link #publishDue()} (final first, then {@code IN_PROGRESS}).
 * <p>
 * It is the only writer of the feed and runs on the single scheduler thread, so feed entries are committed
 * in {@code feed_seq} order: a reader's cursor never skips an entry committed later.
 */
@Component
public class Executor {

    private static final Logger log = LoggerFactory.getLogger(Executor.class);

    private final ExecutionDao dao;
    private final UuidV7Generator ids;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final CarrierProperties properties;

    Executor(ExecutionDao dao, UuidV7Generator ids, Clock clock, TransactionTemplate transaction,
             CarrierProperties properties) {
        this.dao = dao;
        this.ids = ids;
        this.clock = clock;
        this.transaction = transaction;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${carrier.executor-interval-ms:1000}")
    void tick() {
        if (properties.executorEnabled()) {
            runOnce();
        }
    }

    /**
     * One executor pass: executes a batch of accepted operations, then publishes the deferred results that are
     * due ({@link #publishDue()}); returns how many operations were executed.
     */
    public int runOnce() {
        int executed = executeBatch();
        publishDue();
        return executed;
    }

    /**
     * Publishes the deferred feed entries ({@code delayed}) that are due, oldest first: the final result, then
     * {@code IN_PROGRESS}, with the message ids assigned at execution and {@code occurredAt} = execution time.
     * The due time is stored in the operation row, so pending publications survive a restart. Runs on the same
     * single scheduler thread as execution, so the feed keeps one writer. Returns how many operations were
     * published.
     */
    public int publishDue() {
        Integer published = transaction.execute(status -> {
            List<ExecutionDao.DuePublication> batch =
                    dao.lockDuePublications(clock.instant(), properties.executorBatchSize());
            int count = 0;
            for (ExecutionDao.DuePublication due : batch) {
                if (dao.clearPublishAt(due.operationId())) {
                    publishDeferred(due);
                    log.info("Published deferred result of operation {} (due {}, executed {})",
                            due.operationId(), due.publishAt(), due.executedAt());
                    count++;
                }
            }
            return count;
        });
        return published == null ? 0 : published;
    }

    /**
     * Deferred publication: final result first, then {@code IN_PROGRESS}, so the reader sees a late
     * {@code IN_PROGRESS} after the result. Kept in one place so it can go through the shared (duplicate-aware)
     * publication method.
     */
    private void publishDeferred(ExecutionDao.DuePublication due) {
        Outcome outcome = due.outcome();
        dao.publish(due.messageIds().result(), due.operationId(), OperationDao.FINAL_SEQUENCE, outcome.status(),
                outcome.trackingNumber(), outcome.price(), outcome.reason(), due.executedAt());
        dao.publish(due.messageIds().inProgress(), due.operationId(), 1, ResultStatus.IN_PROGRESS, null, null, null,
                due.executedAt());
    }

    private int executeBatch() {
        Integer executed = transaction.execute(status -> {
            List<ExecutionDao.Pending> batch = dao.lockAccepted(properties.executorBatchSize());
            int count = 0;
            for (ExecutionDao.Pending pending : batch) {
                if (execute(pending)) {
                    count++;
                }
            }
            return count;
        });
        if (executed != null && executed > 0) {
            log.info("Executed {} operation(s)", executed);
        }
        return executed == null ? 0 : executed;
    }

    private boolean execute(ExecutionDao.Pending pending) {
        Outcome outcome = OutcomePolicy.decide(pending.operationId(), pending.weightKg(), pending.deliveryAddress());
        Instant now = clock.instant();
        ExecutionDao.MessageIds messageIds = new ExecutionDao.MessageIds(ids.newId(), ids.newId());
        Instant publishAt = pending.faults().delayed()
                ? now.plus(properties.faults().delayedResultDelay())
                : null;
        if (!dao.markExecuted(pending.operationId(), outcome, messageIds, now, publishAt)) {
            return false;
        }
        if (publishAt != null) {
            log.warn("Fault delayed: result of operation {} deferred until {}", pending.operationId(), publishAt);
            return true;
        }
        dao.publish(messageIds.inProgress(), pending.operationId(), 1, ResultStatus.IN_PROGRESS, null, null, null,
                now);
        dao.publish(messageIds.result(), pending.operationId(), OperationDao.FINAL_SEQUENCE, outcome.status(),
                outcome.trackingNumber(), outcome.price(), outcome.reason(), now);
        return true;
    }
}
