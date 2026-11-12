package com.acme.carrier.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** A fixed clock, so stored instants are predictable. */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfiguration {

    public static final Instant NOW = Instant.parse("2026-11-12T09:00:00Z");

    @Bean
    @Primary
    Clock testClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
