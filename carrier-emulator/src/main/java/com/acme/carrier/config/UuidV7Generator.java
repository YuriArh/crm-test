package com.acme.carrier.config;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Random;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * UUID version 7 (RFC 9562) for feed message ids. A copy of the idea in {@code dispatch}, on purpose:
 * the emulator stands in for an external system and shares no classes with it.
 */
@Component
public class UuidV7Generator {

    private final Clock clock;
    private final Random random = new SecureRandom();

    public UuidV7Generator(Clock clock) {
        this.clock = clock;
    }

    public UUID newId() {
        long timestamp = clock.millis() & 0xFFFF_FFFF_FFFFL;
        long mostSignificant = (timestamp << 16) | 0x7000L | random.nextInt(1 << 12);
        long leastSignificant = (random.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(mostSignificant, leastSignificant);
    }
}
