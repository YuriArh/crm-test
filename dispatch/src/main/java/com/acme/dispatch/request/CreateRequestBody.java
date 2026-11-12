package com.acme.dispatch.request;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/requests}. The rule "deadline is not before today in the business zone"
 * needs the clock, so it is checked in {@code RequestService}.
 */
public record CreateRequestBody(
        @NotBlank @Size(max = 200) String cargoDescription,
        @NotNull
        @DecimalMin(value = "0.1", message = WEIGHT_RANGE)
        @DecimalMax(value = "20000", message = WEIGHT_RANGE)
        @Digits(integer = 5, fraction = 1, message = "must have at most one decimal place, e.g. 12.5")
        BigDecimal weightKg,
        @NotBlank @Size(max = 500) String deliveryAddress,
        @NotNull LocalDate deadline) {

    static final String WEIGHT_RANGE = "must be between 0.1 and 20000 kg";

    /** Canonical weight before validation, so {@code 12.50} passes the one-decimal check like {@code 12.5}. */
    public CreateRequestBody {
        weightKg = DeliveryContent.canonicalWeight(weightKg);
    }

    public DeliveryContent toContent() {
        return new DeliveryContent(cargoDescription.strip(), weightKg, deliveryAddress.strip(), deadline);
    }
}
