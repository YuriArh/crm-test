package com.acme.dispatch.exchange.reconcile;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import com.acme.dispatch.carrier.LookupResult;
import com.acme.dispatch.exchange.DeliveryStatus;
import com.acme.dispatch.exchange.inbox.ResultMessage;
import com.acme.dispatch.exchange.inbox.SafeText;

/**
 * What a reconciliation check concludes ({@code docs/specs/reconciliation.md} §2.2–2.4) and when the operation is
 * checked next (§2.1). Pure functions: the caller reads our state under the operation's row lock.
 */
public final class ReconciliationRules {

    /** Longest part of the carrier's answer quoted in the details. */
    static final int EXCERPT_LENGTH = 500;

    private static final Set<DeliveryStatus> NOT_DELIVERED_YET =
            EnumSet.of(DeliveryStatus.PENDING, DeliveryStatus.RETRY_WAIT, DeliveryStatus.SENDING);

    private ReconciliationRules() {
    }

    /** The carrier's answer with its result checked by the same strict rules as a feed entry. */
    public static CarrierAnswer answer(LookupResult lookup) {
        return switch (lookup) {
            case LookupResult.NotFound notFound ->
                    new CarrierAnswer.NotFound(notFound.code(), notFound.message(), notFound.raw());
            case LookupResult.Invalid invalid ->
                    new CarrierAnswer.Invalid(invalid.reason(), invalid.code(), invalid.message(), invalid.raw());
            case LookupResult.Found found -> known(found);
        };
    }

    private static CarrierAnswer known(LookupResult.Found found) {
        if (found.result() == null) {
            return new CarrierAnswer.Known(found.state(), null, found.raw());
        }
        ResultMessage result = ResultMessage.parse(found.result());
        if (!result.isValid()) {
            return new CarrierAnswer.Invalid("result is against the contract: " + result.invalidReason(), null, null,
                    found.raw());
        }
        if (!result.isFinal()) {
            // The contract shows only final results here; an intermediate one tells us nothing new.
            return new CarrierAnswer.Known(found.state(), null, found.raw());
        }
        if (!"EXECUTED".equals(found.state())) {
            return new CarrierAnswer.Invalid("a final result while the operation is " + found.state(), null, null,
                    found.raw());
        }
        return new CarrierAnswer.Known(found.state(), result, found.raw());
    }

    /** Whether the answer shows that the carrier knows the operation. */
    public static boolean carrierKnows(CarrierAnswer answer) {
        return answer instanceof CarrierAnswer.Known;
    }

    /** The outcome of comparing our state with the carrier's answer (the table of §2.4). */
    public static ReconciliationOutcome decide(Local local, CarrierAnswer answer) {
        return switch (answer) {
            case CarrierAnswer.Unavailable unavailable -> ReconciliationOutcome.ERROR;
            case CarrierAnswer.Invalid invalid -> ReconciliationOutcome.ERROR;
            case CarrierAnswer.NotFound notFound -> NOT_DELIVERED_YET.contains(local.delivery())
                    ? ReconciliationOutcome.MATCH
                    : ReconciliationOutcome.EXTERNAL_MISSING;
            case CarrierAnswer.Known known -> compare(local.result(), known.content());
        };
    }

    private static ReconciliationOutcome compare(String ours, String carriers) {
        if (carriers == null) {
            return ours == null ? ReconciliationOutcome.MATCH : ReconciliationOutcome.CONFLICT;
        }
        if (ours == null) {
            return ReconciliationOutcome.EXTERNAL_AHEAD;
        }
        return ours.equals(carriers) ? ReconciliationOutcome.MATCH : ReconciliationOutcome.CONFLICT;
    }

    /**
     * Why the check concluded what it did: our reading, then the carrier's own code and message if it sent them,
     * then the start of its answer.
     */
    public static String details(ReconciliationOutcome outcome, Local local, CarrierAnswer answer) {
        StringBuilder text = new StringBuilder(reading(outcome, local, answer));
        String code = switch (answer) {
            case CarrierAnswer.NotFound notFound -> carrierText(notFound.code(), notFound.message());
            case CarrierAnswer.Invalid invalid -> carrierText(invalid.code(), invalid.message());
            default -> null;
        };
        if (code != null) {
            text.append("; carrier: ").append(code);
        }
        String raw = answer.raw();
        if (raw != null && !raw.isEmpty()) {
            text.append("; response: ").append(SafeText.of(raw, EXCERPT_LENGTH));
        }
        return text.toString();
    }

    private static String reading(ReconciliationOutcome outcome, Local local, CarrierAnswer answer) {
        return switch (answer) {
            case CarrierAnswer.Unavailable unavailable -> "Carrier unavailable: " + unavailable.error();
            case CarrierAnswer.Invalid invalid -> "Carrier answer is against the contract: " + invalid.reason();
            case CarrierAnswer.NotFound notFound -> "Carrier does not know the operation; our delivery is "
                    + local.delivery();
            case CarrierAnswer.Known known -> switch (outcome) {
                case EXTERNAL_AHEAD -> "Carrier has result " + known.content() + ", we have none";
                case CONFLICT -> known.content() == null
                        ? "Carrier has no result yet (" + known.state() + "), ours is " + local.result()
                        : "Carrier result " + known.content() + " differs from ours " + local.result();
                default -> known.content() == null
                        ? "Carrier " + known.state() + ", no result on either side"
                        : "Results match: " + known.content();
            };
        };
    }

    private static String carrierText(String code, String message) {
        if (code == null && message == null) {
            return null;
        }
        return (code == null ? "" : code) + (code != null && message != null ? ": " : "")
                + (message == null ? "" : message);
    }

    /**
     * When the operation is checked next: the pause doubles while the answer stays the same, up to {@code max}, and
     * goes back to {@code base} when it changes. An unavailable carrier is not about the operation, so its pause stays
     * {@code base}.
     *
     * @param previous the last check of the operation, or {@code null} if there was none since its schedule was reset
     */
    public static Instant nextCheck(Previous previous, Signature current, Instant now, Duration base, Duration max,
                                    boolean unavailable) {
        if (unavailable || previous == null || !previous.signature().equals(current)) {
            return now.plus(base);
        }
        Duration doubled = Duration.between(previous.reconciledAt(), previous.nextAt()).multipliedBy(2);
        Duration pause = doubled.compareTo(max) > 0 ? max : doubled.compareTo(base) < 0 ? base : doubled;
        return now.plus(pause);
    }

    /** A request's result in the canonical form of {@link ResultMessage#content()}; {@code null} if not final. */
    public static String localContent(String businessStatus, String trackingNumber, BigDecimal price, String reason) {
        if (!"SUCCEEDED".equals(businessStatus) && !"REJECTED".equals(businessStatus)) {
            return null;
        }
        return ResultMessage.content(businessStatus, trackingNumber, price, reason);
    }

    /**
     * Our side when the check is decided.
     *
     * @param result our final result in canonical form, or {@code null}
     */
    public record Local(DeliveryStatus delivery, String result) {
    }

    /** What makes two checks "the same answer" for the pause. */
    public record Signature(ReconciliationOutcome outcome, String carrierState, String carrierResultStatus,
                            DeliveryStatus delivery) {
    }

    /** The operation's last check: when it was, when the next one was due, and what it found. */
    public record Previous(Instant reconciledAt, Instant nextAt, Signature signature) {
    }

    /** The carrier's answer, its result checked. */
    public sealed interface CarrierAnswer {

        /** The carrier's body as it came, or {@code null} if there was none. */
        String raw();

        record Unavailable(String error) implements CarrierAnswer {
            @Override
            public String raw() {
                return null;
            }
        }

        record Invalid(String reason, String code, String message, String raw) implements CarrierAnswer {
        }

        record NotFound(String code, String message, String raw) implements CarrierAnswer {
        }

        /**
         * The carrier knows the operation.
         *
         * @param result its valid final result, or {@code null} while it has none
         */
        record Known(String state, ResultMessage result, String raw) implements CarrierAnswer {

            /** The result in canonical form, or {@code null}. */
            public String content() {
                return result == null ? null : result.content();
            }
        }
    }
}
