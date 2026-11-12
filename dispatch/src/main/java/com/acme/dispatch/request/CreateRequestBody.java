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
        @NotNull @DecimalMin("0.1") @DecimalMax("20000") @Digits(integer = 5, fraction = 1) BigDecimal weightKg,
        @NotBlank @Size(max = 500) String deliveryAddress,
        @NotNull LocalDate deadline) {

    public DeliveryContent toContent() {
        return new DeliveryContent(cargoDescription.strip(), weightKg, deliveryAddress.strip(), deadline);
    }
}
