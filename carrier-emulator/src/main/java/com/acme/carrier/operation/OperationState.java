package com.acme.carrier.operation;

import java.time.Instant;
import java.util.UUID;

/** Response of {@code GET /operations/{operationId}}: the carrier's state, used by dispatch for reconciliation. */
public record OperationState(
        UUID operationId,
        OperationStateValue state,
        Instant receivedAt,
        Instant executedAt,
        OperationResult result) {
}
