package com.acme.carrier.support;

import com.acme.carrier.fault.FaultModes;
import com.acme.carrier.fault.Faults;

import org.springframework.jdbc.core.simple.JdbcClient;

public final class TestRows {

    private TestRows() {
    }

    /** Removes all operations and feed entries; call in {@code @AfterEach}. */
    public static void deleteAll(JdbcClient jdbc) {
        jdbc.sql("DELETE FROM feed_entry").update();
        jdbc.sql("DELETE FROM operation").update();
    }

    /**
     * Restores shared in-memory state between tests (the Spring context is shared): all fault modes off,
     * the clock back at {@link TestClockConfiguration#NOW}, the fake sleeper cleared. Call in {@code @AfterEach}
     * of every test that touches any of them.
     */
    public static void resetState(FaultModes faults, MutableClock clock, FakeSleeper sleeper) {
        faults.set(Faults.OFF);
        clock.set(TestClockConfiguration.NOW);
        sleeper.reset();
    }
}
