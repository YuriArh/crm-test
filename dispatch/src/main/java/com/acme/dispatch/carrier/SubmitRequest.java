package com.acme.dispatch.carrier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Body of {@code POST /operations} ({@code SubmitOperationRequest} in carrier-api.yaml). */
public record SubmitRequest(
        UUID operationId,
        String cargoDescription,
        BigDecimal weightKg,
        String deliveryAddress,
        LocalDate deadline) {
}
