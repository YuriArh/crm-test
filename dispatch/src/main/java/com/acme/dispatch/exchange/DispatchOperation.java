package com.acme.dispatch.exchange;

import java.time.Instant;
import java.util.UUID;

/** Read model of a {@code dispatch_operation} row: the exchange state of one request. */
public record DispatchOperation(
        UUID requestId,
        UUID operationId,
        String payloadHash,
        DeliveryStatus deliveryStatus,
        int attempts,
        Instant nextAttemptAt,
        Instant lockedUntil,
        String lastError,
        Instant acknowledgedAt,
        String acknowledgedVia,
        int lastAppliedSeq,
        Instant createdAt,
        Instant updatedAt) {
}
