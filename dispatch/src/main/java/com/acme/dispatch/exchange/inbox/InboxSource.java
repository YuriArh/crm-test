package com.acme.dispatch.exchange.inbox;

/** Where a result came from: the feed, or reconciliation (later). Same processing for both. */
public enum InboxSource {
    FEED,
    RECONCILIATION
}
