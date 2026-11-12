package com.acme.dispatch.idempotency;

import java.util.UUID;

/**
 * Result of {@link IdempotencyService#execute}: the response to return to the client and whether it
 * was replayed from an earlier call with the same key ({@code replayed = true}) or produced now.
 */
public record IdempotentOutcome<T>(int status, T body, UUID resourceId, boolean replayed) {
}
