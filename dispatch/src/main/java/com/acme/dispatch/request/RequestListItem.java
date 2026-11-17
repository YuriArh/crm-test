package com.acme.dispatch.request;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.acme.dispatch.exchange.DeliveryStatus;

/** A row of the request registry; {@code openProblems} is {@code null} for initiators. */
public record RequestListItem(
        UUID id,
        String number,
        String cargoDescription,
        LocalDate deadline,
        BusinessStatus businessStatus,
        DeliveryStatus deliveryStatus,
        boolean overdue,
        PersonRef owner,
        PersonRef assignee,
        Instant createdAt,
        Integer openProblems) {
}
