package com.acme.carrier.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Emulator settings.
 *
 * @param executorEnabled   whether the background executor runs on its schedule (off in integration tests,
 *                          which call it directly)
 * @param executorBatchSize operations executed per pass
 */
@ConfigurationProperties("carrier")
public record CarrierProperties(
        @DefaultValue("true") boolean executorEnabled,
        @DefaultValue("50") int executorBatchSize) {
}
