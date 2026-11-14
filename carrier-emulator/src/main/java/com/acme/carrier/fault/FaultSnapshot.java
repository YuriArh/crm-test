package com.acme.carrier.fault;

import java.util.ArrayList;
import java.util.List;

/**
 * Fault modes an operation remembered when it was first received ({@code operation.fault_duplicate},
 * {@code operation.fault_delayed}). They act at execution and publication, whatever the switches are by then.
 */
public record FaultSnapshot(boolean duplicate, boolean delayed) {

    public static final FaultSnapshot NONE = new FaultSnapshot(false, false);

    /** {@code AdminOperation.faults} in carrier-api.yaml. */
    public List<String> names() {
        List<String> names = new ArrayList<>(2);
        if (duplicate) {
            names.add("DUPLICATE");
        }
        if (delayed) {
            names.add("DELAYED");
        }
        return List.copyOf(names);
    }
}
