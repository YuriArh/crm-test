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
 * The {@code loseResponse} fault mode ({@code docs/specs/carrier-faults.md} §3.2): holds the response to the first
 * receipt of an operation until the operation is executed and {@code carrier.faults.lose-response-delay} has passed
 * since the receipt started, so the client gives up waiting and the response is lost after execution.
 * <p>
 * Called after the receipt transaction has committed; it holds no transaction and no database connection while
 * pausing (each state check borrows a connection for one query).
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

    /**
     * Returns once the operation is executed and the delay has passed since {@code startedAt}, or once the delay
     * has passed if the operation is still not executed (logged as a warning).
     */
    public void hold(UUID operationId, Instant startedAt) {
        log.warn("loseResponse: holding the response for operationId {} until it is executed and {} has passed",
                operationId, delay);
        boolean executed = false;
        // Pauses requested so far: a lower bound of the elapsed time, so the loop ends even if the clock stands still.
        Duration slept = Duration.ZERO;
        try {
            while (true) {
                if (!executed) {
                    executed = isExecuted(operationId);
                }
                Duration elapsed = max(Duration.between(startedAt, clock.instant()), slept);
                Duration left = delay.minus(elapsed);
                if (!left.isPositive()) {
                    break;
                }
                Duration pause = executed || left.compareTo(POLL_INTERVAL) < 0 ? left : POLL_INTERVAL;
                sleeper.sleep(pause);
                slept = slept.plus(pause);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("loseResponse: hold for operationId {} interrupted, responding now", operationId);
            return;
        }
        if (executed || isExecuted(operationId)) {
            log.warn("loseResponse: releasing the held response for operationId {} (executed)", operationId);
        } else {
            log.warn("loseResponse: operationId {} is still not executed after {}, responding anyway",
                    operationId, delay);
        }
    }

    private static Duration max(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    private boolean isExecuted(UUID operationId) {
        return operations.find(operationId)
                .map(operation -> operation.state() == OperationStateValue.EXECUTED)
                .orElse(false);
    }
}
