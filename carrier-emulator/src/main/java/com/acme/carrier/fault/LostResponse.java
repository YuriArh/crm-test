package com.acme.carrier.fault;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.acme.carrier.config.CarrierProperties;
import com.acme.carrier.operation.OperationDao;
import com.acme.carrier.operation.OperationStateValue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Fault mode {@code loseResponse}: holds the response to the first receipt of an operation until the operation is
 * executed and {@code carrier.faults.lose-response-delay} has passed since the receipt started, so the client has
 * given up waiting and the response is lost after execution. If the operation is not executed by then, the
 * response is released anyway.
 * <p>
 * Called after the receipt transaction has committed. It holds no transaction and no database connection while
 * pausing: each state check borrows a connection for one query.
 */
@Component
public class LostResponse {

    /** How often the held receipt checks whether the operation has been executed. */
    static final Duration POLL_INTERVAL = Duration.ofMillis(200);

    private static final Logger log = LoggerFactory.getLogger(LostResponse.class);

    private final OperationDao operations;
    private final Sleeper sleeper;
    private final Clock clock;
    private final Duration delay;

    LostResponse(OperationDao operations, Sleeper sleeper, Clock clock, CarrierProperties properties) {
        this.operations = operations;
        this.sleeper = sleeper;
        this.clock = clock;
        this.delay = properties.faults().loseResponseDelay();
    }

    /** Returns once the delay has passed since {@code startedAt}; polls the operation's state until executed. */
    public void hold(UUID operationId, Instant startedAt) {
        log.warn("Fault loseResponse: response to operation {} held until it is executed and {} has passed",
                operationId, delay);
        boolean executed = false;
        // The pauses taken so far bound the elapsed time from below, so the hold ends even if the clock stands still.
        Duration slept = Duration.ZERO;
        try {
            Duration left;
            while ((left = delay.minus(max(Duration.between(startedAt, clock.instant()), slept))).isPositive()) {
                executed = executed || isExecuted(operationId);
                Duration pause = executed ? left : min(left, POLL_INTERVAL);
                sleeper.sleep(pause);
                slept = slept.plus(pause);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Fault loseResponse: hold of operation {} interrupted, responding now", operationId);
            return;
        }
        if (executed || isExecuted(operationId)) {
            log.warn("Fault loseResponse: operation {} executed, releasing the held response", operationId);
        } else {
            log.warn("Fault loseResponse: operation {} still not executed after {}, releasing the response anyway",
                    operationId, delay);
        }
    }

    private boolean isExecuted(UUID operationId) {
        return operations.find(operationId)
                .map(operation -> operation.state() == OperationStateValue.EXECUTED)
                .orElse(false);
    }

    private static Duration max(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
