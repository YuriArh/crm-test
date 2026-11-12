package com.acme.carrier.operation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /operations} ({@code SubmitOperationRequest} in carrier-api.yaml). Text is stripped and
 * the weight canonicalised on creation, so equal content compares equal ({@code 12.50} = {@code 12.5}).
 */
public record SubmitOperationRequest(
        @NotNull UUID operationId,
        @NotBlank @Size(max = 200) String cargoDescription,
        @NotNull
        @DecimalMin(value = "0.1", message = WEIGHT_RANGE)
        @DecimalMax(value = "20000", message = WEIGHT_RANGE)
        @Digits(integer = 5, fraction = 1, message = "must have at most one decimal place")
        BigDecimal weightKg,
        @NotBlank @Size(max = 500) String deliveryAddress,
        @NotNull LocalDate deadline) {

    static final String WEIGHT_RANGE = "must be between 0.1 and 20000";

    public SubmitOperationRequest {
        cargoDescription = cargoDescription == null ? null : cargoDescription.strip();
        deliveryAddress = deliveryAddress == null ? null : deliveryAddress.strip();
        weightKg = canonicalWeight(weightKg);
    }

    /** Weight without trailing zeros and never in exponent form: {@code 12.50} → {@code 12.5}, {@code 2E+4} → {@code 20000}. */
    static BigDecimal canonicalWeight(BigDecimal weight) {
        if (weight == null) {
            return null;
        }
        BigDecimal stripped = weight.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
