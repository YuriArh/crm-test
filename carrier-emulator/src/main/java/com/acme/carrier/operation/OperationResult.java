package com.acme.carrier.operation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A published result of an operation ({@code OperationResult} in carrier-api.yaml). */
public record OperationResult(
        UUID messageId,
        UUID operationId,
        int sequence,
        ResultStatus status,
        String trackingNumber,
        BigDecimal price,
        String reason,
        Instant occurredAt) {
}
