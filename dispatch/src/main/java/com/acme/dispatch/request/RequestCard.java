package com.acme.dispatch.request;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.acme.dispatch.exchange.DeliveryStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Full view of one request.
 *
 * @param result   {@code null} until the carrier reports a final result
 * @param exchange technical exchange state; {@code null} (and omitted from JSON) for initiators
 */
public record RequestCard(
        UUID id,
        String number,
        String cargoDescription,
        BigDecimal weightKg,
        String deliveryAddress,
        LocalDate deadline,
        String comment,
        PersonRef owner,
        PersonRef assignee,
        BusinessStatus businessStatus,
        BusinessResult result,
        DeliveryStatus deliveryStatus,
        UUID operationId,
        boolean overdue,
        Instant createdAt,
        Instant updatedAt,
        long version,
        @JsonInclude(JsonInclude.Include.NON_NULL) ExchangeInfo exchange) {
}
