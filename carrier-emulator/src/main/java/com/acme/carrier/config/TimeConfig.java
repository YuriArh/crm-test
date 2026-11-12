package com.acme.carrier.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TimeConfig {

    /** Single source of "now", so tests can substitute their own clock. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
