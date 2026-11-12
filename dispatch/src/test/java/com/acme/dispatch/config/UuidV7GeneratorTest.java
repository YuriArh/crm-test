package com.acme.dispatch.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class UuidV7GeneratorTest {

    static final Instant T = Instant.parse("2026-11-12T09:00:00.123Z");

    @Test
    void producesVersion7WithRfcVariant() {
        UUID id = generatorAt(T).newId();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void embedsTheClockMillisecondsInTheFirst48Bits() {
        UUID id = generatorAt(T).newId();

        assertThat(id.getMostSignificantBits() >>> 16).isEqualTo(T.toEpochMilli());
    }

    @Test
    void laterIdsSortAfterEarlierOnes() {
        UUID earlier = generatorAt(T).newId();
        UUID later = generatorAt(T.plusMillis(1)).newId();

        assertThat(later).isGreaterThan(earlier);
        assertThat(later.toString()).isGreaterThan(earlier.toString());
    }

    @Test
    void idsAreUniqueWithinTheSameMillisecond() {
        UuidV7Generator generator = generatorAt(T);
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(generator.newId());
        }

        assertThat(ids).hasSize(10_000);
    }

    private static UuidV7Generator generatorAt(Instant instant) {
        return new UuidV7Generator(Clock.fixed(instant, ZoneOffset.UTC));
    }
}
