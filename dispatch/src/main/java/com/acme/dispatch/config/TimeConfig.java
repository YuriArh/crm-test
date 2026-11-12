package com.acme.dispatch.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TimeConfig {

    /** Single source of "now", so tests can substitute a fixed clock. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
