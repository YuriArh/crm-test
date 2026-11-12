package com.acme.dispatch.config;

import java.time.ZoneId;

import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Application-level settings bound from the {@code app.*} properties.
 *
 * @param timezone single business time zone used for deadlines and period
 *                 boundaries in the UI, the API and calculations
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(@NotNull ZoneId timezone) {
}
