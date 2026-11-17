package com.acme.dispatch.exchange;

import java.math.BigDecimal;

import com.acme.dispatch.exchange.inbox.ResultMessage;

/**
 * A final result as it was at some moment: one side of a discrepancy kept for history
 * ({@code docs/specs/conflict-history.md}). Our side of a check row and of a problem.
 *
 * @param status {@code SUCCEEDED} or {@code REJECTED}
 */
public record ResultSnapshot(String status, String trackingNumber, BigDecimal price, String reason) {

    /** A request's result from its fields; {@code null} unless its business status is final. */
    public static ResultSnapshot of(String businessStatus, String trackingNumber, BigDecimal price, String reason) {
        if (!"SUCCEEDED".equals(businessStatus) && !"REJECTED".equals(businessStatus)) {
            return null;
        }
        return new ResultSnapshot(businessStatus, trackingNumber, price, reason);
    }

    /** The comparison key of {@link ResultMessage#content()}, so both sides compare as equals. */
    public String content() {
        return ResultMessage.content(status, trackingNumber, price, reason);
    }

    /** The comparison key of a result, {@code null} when there is none. */
    public static String contentOf(ResultSnapshot snapshot) {
        return snapshot == null ? null : snapshot.content();
    }
}
