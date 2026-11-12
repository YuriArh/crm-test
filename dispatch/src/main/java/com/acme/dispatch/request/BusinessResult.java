package com.acme.dispatch.request;

import java.math.BigDecimal;
import java.time.Instant;

/** Business result reported by the carrier: tracking number and price, or a rejection reason. */
public record BusinessResult(String trackingNumber, BigDecimal price, String rejectionReason, Instant resultAt) {
}
