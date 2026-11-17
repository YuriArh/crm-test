package com.acme.dispatch.exchange;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    static final Duration INITIAL = Duration.ofSeconds(5);
    static final Duration MAX = Duration.ofMinutes(5);

    @Test
    void pauseDoublesFromTheInitialValue() {
        RetryPolicy policy = new RetryPolicy(INITIAL, MAX, 8, 0.2, () -> 0.5);   // 0.5 = middle of the jitter

        assertThat(policy.backoff(1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(policy.backoff(2)).isEqualTo(Duration.ofSeconds(10));
        assertThat(policy.backoff(3)).isEqualTo(Duration.ofSeconds(20));
        assertThat(policy.backoff(6)).isEqualTo(Duration.ofSeconds(160));
    }

    @Test
    void pauseIsCapped() {
        RetryPolicy policy = new RetryPolicy(INITIAL, MAX, 8, 0.2, () -> 0.5);

        assertThat(policy.backoff(7)).isEqualTo(MAX);
        assertThat(policy.backoff(40)).isEqualTo(MAX);
    }

    @Test
    void jitterStaysWithinItsShare() {
        assertThat(new RetryPolicy(INITIAL, MAX, 8, 0.2, () -> 0.0).backoff(2)).isEqualTo(Duration.ofSeconds(8));
        assertThat(new RetryPolicy(INITIAL, MAX, 8, 0.2, () -> 0.999999).backoff(2)).isEqualTo(Duration.ofSeconds(12));
    }

    @Test
    void withoutJitterThePauseIsExact() {
        RetryPolicy policy = new RetryPolicy(Duration.ofSeconds(1), Duration.ofMinutes(1), 5, 0.0, () -> 0.0);

        assertThat(policy.backoff(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.backoff(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.backoff(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(policy.backoff(4)).isEqualTo(Duration.ofSeconds(8));
        assertThat(policy.backoff(7)).isEqualTo(Duration.ofMinutes(1));
        assertThat(policy.canRetry(4)).isTrue();
        assertThat(policy.canRetry(5)).isFalse();
    }

    @Test
    void sendingAndProcessingPoliciesComeFromTheirSettings() {
        ExchangeProperties properties = new ExchangeProperties(true, 20, Duration.ofSeconds(60), INITIAL, MAX, 8, 100,
                Duration.ofSeconds(30), 5, Duration.ofSeconds(1), Duration.ofMinutes(1), Duration.ofMinutes(1),
                Duration.ofMinutes(2), Duration.ofMinutes(5), Duration.ofHours(1), 100);

        RetryPolicy processing = RetryPolicy.forInboxProcessing(properties);
        assertThat(processing.backoff(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(processing.canRetry(5)).isFalse();

        RetryPolicy sending = RetryPolicy.forSending(properties);
        assertThat(sending.canRetry(7)).isTrue();
        assertThat(sending.canRetry(8)).isFalse();
    }

    @Test
    void attemptsAreLimited() {
        RetryPolicy policy = new RetryPolicy(INITIAL, MAX, 8, 0.2, () -> 0.5);

        assertThat(policy.canRetry(7)).isTrue();
        assertThat(policy.canRetry(8)).isFalse();
    }

    @Test
    void allRetriesTakeAboutTenMinutes() {
        RetryPolicy policy = new RetryPolicy(INITIAL, MAX, 8, 0.2, () -> 0.5);
        Duration total = Duration.ZERO;
        for (int attempt = 1; attempt < 8; attempt++) {
            total = total.plus(policy.backoff(attempt));
        }

        // 5 + 10 + 20 + 40 + 80 + 160 + 300 s between the 8 attempts.
        assertThat(total).isEqualTo(Duration.ofSeconds(615));
    }
}
