package com.acme.exchange.system;

import java.time.Clock;
import java.time.Instant;

import com.acme.exchange.config.AppProperties;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
class SystemInfoController {

    private final Clock clock;
    private final AppProperties properties;
    private final String applicationName;

    SystemInfoController(Clock clock, AppProperties properties,
                         @Value("${spring.application.name}") String applicationName) {
        this.clock = clock;
        this.properties = properties;
        this.applicationName = applicationName;
    }

    @GetMapping("/info")
    SystemInfo info() {
        return new SystemInfo(applicationName, Instant.now(clock), properties.timezone().getId());
    }

    record SystemInfo(String application, Instant serverTime, String businessTimezone) {
    }
}
