package com.acme.dispatch.config;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Random;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * UUID version 7 (RFC 9562): 48-bit Unix timestamp in milliseconds, then random bits.
 * Ids created later sort after earlier ones (at millisecond resolution), which keeps B-tree
 * inserts local.
 */
@Component
public class UuidV7Generator implements IdGenerator {

    private static final long TIMESTAMP_MASK = 0xFFFF_FFFF_FFFFL;

    private final Clock clock;
    private final Random random;

    @Autowired
    public UuidV7Generator(Clock clock) {
        this(clock, new SecureRandom());
    }

    UuidV7Generator(Clock clock, Random random) {
        this.clock = clock;
        this.random = random;
    }

    @Override
    public UUID newId() {
        long timestamp = clock.millis() & TIMESTAMP_MASK;
        long randA = random.nextInt(1 << 12);
        long mostSignificant = (timestamp << 16) | 0x7000L | randA;
        long leastSignificant = (random.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(mostSignificant, leastSignificant);
    }
}
