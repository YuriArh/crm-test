package com.acme.carrier.fault;

import jakarta.validation.constraints.NotNull;

/**
 * Fault mode switches ({@code Faults} in carrier-api.yaml). All four are required in a {@code PUT}.
 * See {@code docs/specs/carrier-faults.md}.
 */
public record Faults(
        @NotNull Boolean unavailable,
        @NotNull Boolean loseResponse,
        @NotNull Boolean duplicate,
        @NotNull Boolean delayed) {

    public static final Faults OFF = new Faults(false, false, false, false);

    /** The modes an operation remembers when it is first received. */
    public FaultSnapshot snapshot() {
        return new FaultSnapshot(duplicate, delayed);
    }
}
