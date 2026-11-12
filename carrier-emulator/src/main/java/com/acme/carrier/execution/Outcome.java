package com.acme.carrier.execution;

import java.math.BigDecimal;

import com.acme.carrier.operation.ResultStatus;

/** Final outcome of an executed operation: a shipment number and price, or a rejection reason. */
public record Outcome(ResultStatus status, String trackingNumber, BigDecimal price, String reason) {

    static Outcome succeeded(String trackingNumber, BigDecimal price) {
        return new Outcome(ResultStatus.SUCCEEDED, trackingNumber, price, null);
    }

    static Outcome rejected(String reason) {
        return new Outcome(ResultStatus.REJECTED, null, null, reason);
    }
}
