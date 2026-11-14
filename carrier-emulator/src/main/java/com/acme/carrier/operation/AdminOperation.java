package com.acme.carrier.operation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** An operation with its content, execution counters and remembered fault modes ({@code AdminOperation} in carrier-api.yaml). */
public record AdminOperation(
        UUID operationId,
        String cargoDescription,
        BigDecimal weightKg,
        String deliveryAddress,
        LocalDate deadline,
        OperationStateValue state,
        Instant receivedAt,
        Instant executedAt,
        OperationResult result,
        int receivedCount,
        int executionCount,
        List<String> faults,
        Instant resultPublishAt) {

    public OperationState toState() {
        return new OperationState(operationId, state, receivedAt, executedAt, result);
    }
}
