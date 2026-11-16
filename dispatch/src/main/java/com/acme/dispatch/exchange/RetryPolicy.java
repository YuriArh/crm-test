package com.acme.dispatch.exchange;

import java.time.Duration;
import java.util.function.DoubleSupplier;

/**
 * Pause before the next attempt: {@code initial × 2^(attempt−1)}, capped at {@code max}, optionally with a random
 * spread. After {@code maxAttempts} attempts there is no next attempt. Used for sending to the carrier and for
 * processing received results.
 */
public final class RetryPolicy {

    static final double JITTER = 0.2;

    private final Duration initial;
    private final Duration max;
    private final int maxAttempts;
    private final double jitter;
    private final DoubleSupplier random;

    /**
     * @param jitter share of random spread around the pause, e.g. {@code 0.2} for ±20 %; {@code 0} for none
     * @param random source of values in {@code [0, 1)} for the spread
     */
    public RetryPolicy(Duration initial, Duration max, int maxAttempts, double jitter, DoubleSupplier random) {
        this.initial = initial;
        this.max = max;
        this.maxAttempts = maxAttempts;
        this.jitter = jitter;
        this.random = random;
    }

    /** Sends to the carrier: ±20 % spread, so that operations failed together do not retry together. */
    public static RetryPolicy forSending(ExchangeProperties properties) {
        return new RetryPolicy(properties.initialBackoff(), properties.maxBackoff(), properties.maxAttempts(),
                JITTER, Math::random);
    }

    /** Processing of received results: no spread, nothing goes to the carrier. */
    public static RetryPolicy forInboxProcessing(ExchangeProperties properties) {
        return new RetryPolicy(properties.inboxInitialBackoff(), properties.inboxMaxBackoff(),
                properties.inboxMaxAttempts(), 0, () -> 0.5);
    }

    /** Whether another attempt is allowed after {@code attemptsMade} attempts. */
    public boolean canRetry(int attemptsMade) {
        return attemptsMade < maxAttempts;
    }

    /** Pause after the attempt number {@code attemptsMade} (1-based) failed. */
    public Duration backoff(int attemptsMade) {
        int exponent = Math.min(Math.max(attemptsMade - 1, 0), 30);
        long baseMillis = Math.min(initial.toMillis() * (1L << exponent), max.toMillis());
        double factor = 1 - jitter + 2 * jitter * random.getAsDouble();
        return Duration.ofMillis(Math.round(baseMillis * factor));
    }
}
