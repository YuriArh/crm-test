package com.acme.carrier.fault;

import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Current fault mode switches. They live in memory only: after an emulator restart all are off.
 * Modes are global: they act on all traffic.
 */
@Component
public class FaultModes {

    private static final Logger log = LoggerFactory.getLogger(FaultModes.class);

    private final AtomicReference<Faults> current = new AtomicReference<>(Faults.OFF);

    public Faults current() {
        return current.get();
    }

    /** Replaces all switches; returns the new state. Every change is logged. */
    public Faults set(Faults faults) {
        Faults previous = current.getAndSet(faults);
        if (!previous.equals(faults)) {
            log.warn("Fault modes changed: {} -> {}", previous, faults);
        }
        return faults;
    }
}
