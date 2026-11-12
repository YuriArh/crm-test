package com.acme.dispatch.exchange;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Background exchange with the carrier ({@code docs/dispatch-design.md} §5).
 *
 * @param enabled          whether the send and feed workers run on their schedule (off in integration tests,
 *                         which call them directly)
 * @param sendBatchSize    operations claimed per send pass
 * @param lease            how long a claimed operation stays in {@code SENDING}; after that it is treated as
 *                         interrupted and goes to {@code UNKNOWN}
 * @param initialBackoff   pause before the second attempt; doubles with every attempt
 * @param maxBackoff       upper bound of the pause
 * @param maxAttempts      attempts before the operation becomes {@code FAILED}
 * @param feedPageSize     entries read from the result feed per request (the carrier allows at most 100)
 * @param feedMaxBackoff   longest pause between feed reads while the carrier is unavailable
 */
@ConfigurationProperties(prefix = "app.exchange")
public record ExchangeProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("20") int sendBatchSize,
        @DefaultValue("60s") Duration lease,
        @DefaultValue("5s") Duration initialBackoff,
        @DefaultValue("5m") Duration maxBackoff,
        @DefaultValue("8") int maxAttempts,
        @DefaultValue("100") int feedPageSize,
        @DefaultValue("30s") Duration feedMaxBackoff) {
}
