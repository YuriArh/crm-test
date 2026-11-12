package com.acme.dispatch.request;

import java.time.Instant;

/** Technical exchange state of a request; shown to operators only. */
public record ExchangeInfo(int attempts, Instant nextAttemptAt, String lastError,
                           Instant acknowledgedAt, String acknowledgedVia) {
}
