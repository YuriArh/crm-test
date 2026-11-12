package com.acme.dispatch.request;

import java.time.LocalDate;

/**
 * Overdue rule (NF07): the deadline is a date in the business time zone, inclusive.
 * A request is overdue when it has no final result and today is after the deadline.
 * The registry SQL filter implements the same rule with the same {@code today}.
 */
public final class OverduePolicy {

    private OverduePolicy() {
    }

    public static boolean isOverdue(BusinessStatus status, LocalDate deadline, LocalDate today) {
        return !status.isFinal() && today.isAfter(deadline);
    }
}
