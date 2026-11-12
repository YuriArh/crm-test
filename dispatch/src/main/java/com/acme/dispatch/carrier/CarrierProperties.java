package com.acme.dispatch.carrier;

import java.net.URI;
import java.time.Duration;

import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Connection to the carrier ({@code contracts/carrier-api.yaml}).
 *
 * @param baseUrl        carrier root URL, e.g. {@code http://carrier-emulator:8081}
 * @param connectTimeout time to establish a connection; failing here means the request was not sent
 * @param readTimeout    time to wait for the whole response; failing here means the outcome is unknown
 */
@Validated
@ConfigurationProperties(prefix = "app.carrier")
public record CarrierProperties(
        @NotNull URI baseUrl,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("5s") Duration readTimeout) {
}
