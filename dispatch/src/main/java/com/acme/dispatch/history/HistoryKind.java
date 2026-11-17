package com.acme.dispatch.history;

/**
 * A kind of history entry, in the order things happen at one instant (one transaction): an attempt before the
 * acknowledgement it brought, a record before the result it changed, a result before the problem it opened, a
 * decision last. The feed is ordered by time, then by this order.
 */
public enum HistoryKind {
    CREATED(true),
    DELIVERY_RETRIED(false),
    SEND_ATTEMPT(false),
    RECONCILIATION(false),
    RESULT_RECEIVED(false),
    RESULT_CHANGED(true),
    DELIVERY_ACKNOWLEDGED(true),
    EDITED(true),
    PROBLEM_OPENED(false),
    PROBLEM_RESOLVED(false);

    private final boolean business;

    HistoryKind(boolean business) {
        this.business = business;
    }

    /** Part of the business history an initiator sees; the rest is the exchange, for operators. */
    public boolean isBusiness() {
        return business;
    }
}
