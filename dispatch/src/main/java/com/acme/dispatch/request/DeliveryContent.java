package com.acme.dispatch.request;

import java.math.BigDecimal;
import java.time.LocalDate;

/** The immutable part of a delivery request — exactly what is sent to the carrier. */
public record DeliveryContent(String cargoDescription, BigDecimal weightKg, String deliveryAddress, LocalDate deadline) {

    /**
     * The one rule for comparing weights: {@code 12.50} = {@code 12.5}, {@code 20000.0} = {@code 20000}.
     * Never negative scale, so the value prints as a plain number ({@code 20000}, not {@code 2E+4}).
     */
    public static BigDecimal canonicalWeight(BigDecimal weight) {
        if (weight == null) {
            return null;
        }
        BigDecimal stripped = weight.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
