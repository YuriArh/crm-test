package com.acme.dispatch.request;

import java.math.BigDecimal;
import java.time.LocalDate;

/** The immutable part of a delivery request — exactly what is sent to the carrier. */
public record DeliveryContent(String cargoDescription, BigDecimal weightKg, String deliveryAddress, LocalDate deadline) {
}
