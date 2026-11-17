package com.acme.dispatch.exchange;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Background exchange with the carrier ({@code docs/dispatch-design.md} §5).
 *
 * @param enabled          whether the send and feed workers run on their schedule ({@code EXCHANGE_ENABLED}); off
 *                         stops the exchange with the carrier, e.g. while the carrier is under maintenance, and
 *                         leaves the requests API working
 * @param sendsPerPass     most operations sent in one pass; each is claimed right before it is sent
 * @param lease            how long a claimed operation stays in {@code SENDING}; after that it is treated as
 *                         interrupted and goes to {@code UNKNOWN}; it is also the lifetime of an open send
 *                         attempt, so it must exceed the carrier connect timeout plus the read timeout
 * @param initialBackoff   pause before the second attempt; doubles with every attempt
 * @param maxBackoff       upper bound of the pause
 * @param maxAttempts      attempts before the operation becomes {@code FAILED}
 * @param feedPageSize     entries read from the result feed per request (the carrier allows at most 100)
 * @param feedMaxBackoff   longest pause between feed reads while the carrier is unavailable or its feed is broken
 * @param inboxMaxAttempts      attempts to process a received result before it becomes {@code FAILED}
 * @param inboxInitialBackoff   pause before the second processing attempt; doubles with every attempt
 * @param inboxMaxBackoff       upper bound of that pause
 * @param reconcileInterval     pause between background reconciliation passes
 * @param reconcileThreshold    how long an acknowledged operation without a result waits before it is reconciled
 * @param reconcilePause        pause before the next check of an operation; doubles while the answer stays the same
 * @param reconcilePauseMax     upper bound of that pause
 * @param reconcilePerPass      most operations checked in one background pass
 */
@ConfigurationProperties(prefix = "app.exchange")
public record ExchangeProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("20") int sendsPerPass,
        @DefaultValue("60s") Duration lease,
        @DefaultValue("5s") Duration initialBackoff,
        @DefaultValue("5m") Duration maxBackoff,
        @DefaultValue("8") int maxAttempts,
        @DefaultValue("100") int feedPageSize,
        @DefaultValue("30s") Duration feedMaxBackoff,
        @DefaultValue("5") int inboxMaxAttempts,
        @DefaultValue("1s") Duration inboxInitialBackoff,
        @DefaultValue("1m") Duration inboxMaxBackoff,
        @DefaultValue("1m") Duration reconcileInterval,
        @DefaultValue("2m") Duration reconcileThreshold,
        @DefaultValue("5m") Duration reconcilePause,
        @DefaultValue("1h") Duration reconcilePauseMax,
        @DefaultValue("100") int reconcilePerPass) {
}
