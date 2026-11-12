package com.acme.dispatch.exchange;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    static final Duration INITIAL = Duration.ofSeconds(5);
    static final Duration MAX = Duration.ofMinutes(5);

    @Test
    void pauseDoublesFromTheInitialValue() {
        RetryPolicy policy = new RetryPolicy(INITIAL, MAX, 8, () -> 0.5);   // 0.5 = no jitter

        assertThat(policy.backoff(1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(policy.backoff(2)).isEqualTo(Duration.ofSeconds(10));
        assertThat(policy.backoff(3)).isEqualTo(Duration.ofSeconds(20));
        assertThat(policy.backoff(6)).isEqualTo(Duration.ofSeconds(160));
    }

    @Test
    void pauseIsCapped() {
        RetryPolicy policy = new RetryPolicy(INITIAL, MAX, 8, () -> 0.5);

        assertThat(policy.backoff(7)).isEqualTo(MAX);
        assertThat(policy.backoff(40)).isEqualTo(MAX);
    }

    @Test
    void jitterStaysWithinTwentyPercent() {
        assertThat(new RetryPolicy(INITIAL, MAX, 8, () -> 0.0).backoff(2)).isEqualTo(Duration.ofSeconds(8));
        assertThat(new RetryPolicy(INITIAL, MAX, 8, () -> 0.999999).backoff(2)).isEqualTo(Duration.ofSeconds(12));
    }

    @Test
    void attemptsAreLimited() {
        RetryPolicy policy = new RetryPolicy(INITIAL, MAX, 8, () -> 0.5);

        assertThat(policy.canRetry(7)).isTrue();
        assertThat(policy.canRetry(8)).isFalse();
    }

    @Test
    void allRetriesTakeAboutTenMinutes() {
        RetryPolicy policy = new RetryPolicy(INITIAL, MAX, 8, () -> 0.5);
        Duration total = Duration.ZERO;
        for (int attempt = 1; attempt < 8; attempt++) {
            total = total.plus(policy.backoff(attempt));
        }

        // 5 + 10 + 20 + 40 + 80 + 160 + 300 s between the 8 attempts.
        assertThat(total).isEqualTo(Duration.ofSeconds(615));
    }
}
