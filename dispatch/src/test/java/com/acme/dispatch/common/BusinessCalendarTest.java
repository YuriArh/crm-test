package com.acme.dispatch.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import com.acme.dispatch.config.AppProperties;

import org.junit.jupiter.api.Test;

class BusinessCalendarTest {

    static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    @Test
    void todayIsTakenInTheBusinessZoneNotUtc() {
        // 21:30 UTC on the 12th is already 00:30 on the 13th in Moscow (UTC+3).
        BusinessCalendar calendar = calendarAt("2026-11-12T21:30:00Z");

        assertThat(calendar.today()).isEqualTo(LocalDate.of(2026, 11, 13));
    }

    @Test
    void lastMinuteOfTheDayInMoscowIsStillThatDay() {
        BusinessCalendar calendar = calendarAt("2026-11-12T20:59:59Z");

        assertThat(calendar.today()).isEqualTo(LocalDate.of(2026, 11, 12));
    }

    @Test
    void nowIsTheClockInstant() {
        BusinessCalendar calendar = calendarAt("2026-11-12T09:00:00Z");

        assertThat(calendar.now()).isEqualTo(Instant.parse("2026-11-12T09:00:00Z"));
        assertThat(calendar.zone()).isEqualTo(MOSCOW);
    }

    private static BusinessCalendar calendarAt(String instant) {
        return new BusinessCalendar(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC), new AppProperties(MOSCOW));
    }
}
