package com.acme.dispatch.exchange;

import java.time.Duration;
import java.util.function.DoubleSupplier;

/**
 * Pause before the next send attempt: {@code initial × 2^(attempt−1)}, capped at {@code max}, with ±20 % jitter
 * so that operations failed together do not retry together. After {@code maxAttempts} attempts there is no
 * next attempt.
 */
public final class RetryPolicy {

    static final double JITTER = 0.2;

    private final Duration initial;
    private final Duration max;
    private final int maxAttempts;
    private final DoubleSupplier random;

    public RetryPolicy(Duration initial, Duration max, int maxAttempts, DoubleSupplier random) {
        this.initial = initial;
        this.max = max;
        this.maxAttempts = maxAttempts;
        this.random = random;
    }

    public static RetryPolicy from(ExchangeProperties properties) {
        return new RetryPolicy(properties.initialBackoff(), properties.maxBackoff(), properties.maxAttempts(),
                Math::random);
    }

    /** Whether another attempt is allowed after {@code attemptsMade} attempts. */
    public boolean canRetry(int attemptsMade) {
        return attemptsMade < maxAttempts;
    }

    /** Pause after the attempt number {@code attemptsMade} (1-based) failed. */
    public Duration backoff(int attemptsMade) {
        int exponent = Math.min(Math.max(attemptsMade - 1, 0), 30);
        long baseMillis = Math.min(initial.toMillis() * (1L << exponent), max.toMillis());
        double factor = 1 - JITTER + 2 * JITTER * random.getAsDouble();
        return Duration.ofMillis(Math.round(baseMillis * factor));
    }
}
