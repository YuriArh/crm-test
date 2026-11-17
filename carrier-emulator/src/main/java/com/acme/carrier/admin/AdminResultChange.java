package com.acme.carrier.admin;

import java.math.BigDecimal;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PUT /admin/operations/{operationId}/result} ({@code AdminResultChange} in carrier-api.yaml): a new
 * final result by the rules of {@code OperationResult}.
 */
public record AdminResultChange(
        @NotNull Status status,
        @Pattern(regexp = "^TRK-[0-9A-F]{8}$") String trackingNumber,
        @DecimalMin("0") @DecimalMax("9999999999.99") @Digits(integer = 10, fraction = 2) BigDecimal price,
        @Size(min = 1, max = 1000) @Pattern(regexp = "^[^\\x00-\\x1F\\x7F]*$") String reason) {

    public enum Status { SUCCEEDED, REJECTED }

    @AssertTrue(message = "SUCCEEDED needs trackingNumber and price and no reason; REJECTED needs reason and neither of them")
    boolean isConsistent() {
        if (status == null) {
            return true;
        }
        return status == Status.SUCCEEDED
                ? trackingNumber != null && price != null && reason == null
                : reason != null && trackingNumber == null && price == null;
    }
}
