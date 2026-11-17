package com.acme.dispatch.exchange.reconcile;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.acme.dispatch.exchange.ResultSnapshot;
import com.acme.dispatch.request.PersonRef;

/**
 * One recorded reconciliation check as the API shows it ({@code Reconciliation} in dispatch-api.yaml).
 *
 * @param performedBy   the operator of a manual check; {@code null} for background checks
 * @param localResult   our final result when the check was decided; {@code null} when we had none
 * @param carrierState  {@code ACCEPTED}, {@code EXECUTED} or {@code NOT_FOUND}; {@code null} when it did not answer
 * @param carrierResult the carrier's final result; {@code null} when it has none
 */
public record ReconciliationView(
        UUID id,
        Instant checkedAt,
        RunType trigger,
        PersonRef performedBy,
        ReconciliationOutcome outcome,
        boolean acknowledged,
        ResultSnapshot localResult,
        String carrierState,
        CarrierResult carrierResult,
        String details) {

    public record CarrierResult(String status, String trackingNumber, BigDecimal price, String reason, int sequence,
                                String messageId, Instant occurredAt) {
    }
}
