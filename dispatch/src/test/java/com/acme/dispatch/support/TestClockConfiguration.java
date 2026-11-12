package com.acme.dispatch.support;

import java.time.Instant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the application clock with a {@link MutableClock}. The context is shared between test
 * classes, so tests that move the clock must reset it to {@link #START} in {@code @BeforeEach}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfiguration {

    /** 12:00 in Europe/Moscow on 2026-11-12. */
    public static final Instant START = Instant.parse("2026-11-12T09:00:00Z");

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock(START);
    }
}
