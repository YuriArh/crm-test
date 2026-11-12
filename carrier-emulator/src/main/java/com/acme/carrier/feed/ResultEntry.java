package com.acme.carrier.feed;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.acme.carrier.operation.ResultStatus;

/** A feed entry ({@code ResultEntry} in carrier-api.yaml). */
public record ResultEntry(
        long feedSeq,
        UUID messageId,
        UUID operationId,
        int sequence,
        ResultStatus status,
        String trackingNumber,
        BigDecimal price,
        String reason,
        Instant occurredAt) {
}
