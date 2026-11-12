package com.acme.dispatch.common;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import com.acme.dispatch.config.AppProperties;

import org.springframework.stereotype.Component;

/**
 * The single place that turns "now" into business dates (NF07). Everything that needs
 * "today" — validation, the overdue flag, SQL filters — takes it from here.
 */
@Component
public class BusinessCalendar {

    private final Clock clock;
    private final ZoneId zone;

    public BusinessCalendar(Clock clock, AppProperties properties) {
        this.clock = clock;
        this.zone = properties.timezone();
    }

    public Instant now() {
        return clock.instant();
    }

    /** Current date in the business time zone. */
    public LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), zone);
    }

    public ZoneId zone() {
        return zone;
    }
}
