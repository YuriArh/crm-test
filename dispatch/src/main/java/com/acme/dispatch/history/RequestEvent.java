package com.acme.dispatch.history;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A change of a request, or an action on it.
 *
 * @param version   the request's version the change made; {@code null} for {@link RequestEventKind#DELIVERY_RETRIED}
 * @param actorId   who did it; {@code null} — the system
 * @param inboxId   the carrier record whose result was applied ({@link RequestEventKind#RESULT_CHANGED})
 * @param problemId the problem an operator applied the result in, or {@code null}
 */
public record RequestEvent(UUID id, UUID requestId, Long version, Instant occurredAt, UUID actorId,
                           RequestEventKind kind, List<Change> changes, UUID inboxId, UUID problemId) {
}
