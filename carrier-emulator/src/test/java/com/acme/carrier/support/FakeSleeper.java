package com.acme.carrier.support;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.acme.carrier.fault.Sleeper;

/**
 * Replaces real pauses in integration tests: records every requested pause and returns at once.
 * A test may set {@link #onSleep(Runnable)} to act during a pause (e.g. run the executor while a response
 * is held); reset it with {@link #reset()}.
 */
public class FakeSleeper implements Sleeper {

    private final List<Duration> pauses = new CopyOnWriteArrayList<>();
    private volatile Runnable onSleep = () -> { };

    @Override
    public void sleep(Duration duration) {
        pauses.add(duration);
        onSleep.run();
    }

    public void onSleep(Runnable action) {
        this.onSleep = action;
    }

    /** Pauses requested so far, in order. */
    public List<Duration> pauses() {
        return List.copyOf(pauses);
    }

    public void reset() {
        pauses.clear();
        onSleep = () -> { };
    }
}
