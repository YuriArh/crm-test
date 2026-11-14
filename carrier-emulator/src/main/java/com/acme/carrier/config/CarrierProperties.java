package com.acme.carrier.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Emulator settings.
 *
 * @param executorEnabled   whether the background executor runs on its schedule (off in integration tests,
 *                          which call it directly)
 * @param executorBatchSize operations executed per pass
 * @param faults            fault mode delays ({@code docs/specs/carrier-faults.md})
 */
@ConfigurationProperties("carrier")
public record CarrierProperties(
        @DefaultValue("true") boolean executorEnabled,
        @DefaultValue("50") int executorBatchSize,
        @DefaultValue FaultDelays faults) {

    /**
     * @param loseResponseDelay  how long {@code loseResponse} holds the response to the first submit; must exceed
     *                           the client's response timeout (dispatch: 5 s)
     * @param delayedResultDelay how long after execution {@code delayed} publishes the feed entries
     */
    public record FaultDelays(
            @DefaultValue("10s") Duration loseResponseDelay,
            @DefaultValue("60s") Duration delayedResultDelay) {
    }
}
