package com.acme.carrier.support;

import java.time.Instant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * A {@link MutableClock} starting at {@link #NOW}, so stored instants are predictable. The context is shared,
 * so a test that moves the clock resets it with {@link TestRows#resetState}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfiguration {

    public static final Instant NOW = Instant.parse("2026-11-12T09:00:00Z");

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock(NOW);
    }
}
