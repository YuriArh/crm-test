package com.acme.dispatch.idempotency;

import java.util.UUID;

/**
 * What a business action produced: the HTTP status, the response body and the id of the created or
 * changed resource (may be {@code null}). Stored under the idempotency key for replay.
 */
public record IdempotentResponse<T>(int status, T body, UUID resourceId) {
}
