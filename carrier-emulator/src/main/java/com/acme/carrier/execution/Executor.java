package com.acme.carrier.execution;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.acme.carrier.config.CarrierProperties;
import com.acme.carrier.config.UuidV7Generator;
import com.acme.carrier.operation.ResultStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Executes accepted operations in the background and publishes their results to the feed:
 * {@code IN_PROGRESS} (sequence 1), then the final {@code SUCCEEDED}/{@code REJECTED} (sequence 2).
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

    /** Executes one batch of accepted operations; returns how many were executed. */
    public int runOnce() {
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
        if (!dao.markExecuted(pending.operationId(), outcome, now)) {
            return false;
        }
        dao.publish(ids.newId(), pending.operationId(), 1, ResultStatus.IN_PROGRESS, null, null, null, now);
        dao.publish(ids.newId(), pending.operationId(), 2, outcome.status(), outcome.trackingNumber(),
                outcome.price(), outcome.reason(), now);
        return true;
    }
}
