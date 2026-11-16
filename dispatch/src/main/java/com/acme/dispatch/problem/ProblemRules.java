package com.acme.dispatch.problem;

import static com.acme.dispatch.problem.ProblemKind.DELIVERY_FAILED;
import static com.acme.dispatch.problem.ProblemKind.DELIVERY_UNKNOWN;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.acme.dispatch.exchange.DeliveryStatus;
import com.acme.dispatch.exchange.inbox.InboxDao.Decision;

/**
 * When problems open and close and what can be done about them ({@code docs/specs/operator-problems.md} §2.3–2.4).
 * Pure functions, so every case is unit-testable.
 */
public final class ProblemRules {

    private ProblemRules() {
    }

    /**
     * What a delivery transition does to the request's delivery problems.
     *
     * @param status         the status the operation has just moved to
     * @param outcomeUnknown whether the attempt that caused it ended with an unknown outcome (timeout, interrupted
     *                       send): then the carrier may have the request, whatever the new status
     */
    public static DeliveryEffect forDelivery(DeliveryStatus status, boolean outcomeUnknown) {
        return switch (status) {
            case ACKNOWLEDGED -> new DeliveryEffect(Set.of(), Set.of(DELIVERY_UNKNOWN, DELIVERY_FAILED));
            case UNKNOWN -> new DeliveryEffect(Set.of(DELIVERY_UNKNOWN), Set.of());
            case FAILED -> new DeliveryEffect(
                    outcomeUnknown ? Set.of(DELIVERY_FAILED, DELIVERY_UNKNOWN) : Set.of(DELIVERY_FAILED), Set.of());
            case PENDING, SENDING, RETRY_WAIT -> new DeliveryEffect(Set.of(), Set.of());
        };
    }

    /** The problem a processed record opens, if any. */
    public static Optional<ProblemKind> forRecord(Decision outcome) {
        return switch (outcome) {
            case INVALID -> Optional.of(ProblemKind.INVALID_MESSAGE);
            case FAILED -> Optional.of(ProblemKind.MESSAGE_FAILED);
            case CONFLICT -> Optional.of(ProblemKind.RESULT_CONFLICT);
            case APPLIED, STALE -> Optional.empty();
        };
    }

    /**
     * Decisions an operator may take on a problem.
     *
     * @param disputedRecordIsFinal for a result conflict: whether the disputed record is a final result; applying an
     *                              intermediate one over a final result would roll the result back
     */
    public static List<ProblemDecision> decisions(ProblemKind kind, ProblemStatus status,
                                                  boolean disputedRecordIsFinal) {
        if (status != ProblemStatus.OPEN) {
            return List.of();
        }
        return switch (kind) {
            case RESULT_CONFLICT -> disputedRecordIsFinal
                    ? List.of(ProblemDecision.APPLY_CARRIER, ProblemDecision.KEEP_OURS)
                    : List.of(ProblemDecision.KEEP_OURS);
            case INVALID_MESSAGE, MESSAGE_FAILED -> List.of(ProblemDecision.ACKNOWLEDGED);
            case DELIVERY_UNKNOWN, DELIVERY_FAILED -> List.of();
        };
    }

    /** Actions on a request its delivery status allows. */
    public static List<RequestAction> requestActions(DeliveryStatus status) {
        return status == DeliveryStatus.FAILED || status == DeliveryStatus.UNKNOWN
                ? List.of(RequestAction.RETRY)
                : List.of();
    }

    /** Problems to open and to close. */
    public record DeliveryEffect(Set<ProblemKind> open, Set<ProblemKind> close) {

        public DeliveryEffect {
            open = open.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(open));
            close = close.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(close));
        }
    }
}
