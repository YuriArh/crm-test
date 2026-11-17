package com.acme.dispatch.history;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.acme.dispatch.request.PersonRef;

/** One entry of a request's history ({@code HistoryEntry} in {@code dispatch-api.yaml}). */
public sealed interface HistoryEntry {

    UUID id();

    HistoryKind kind();

    Instant at();

    /** Who did it; {@code null} — the system. */
    PersonRef actor();

    record Created(UUID id, HistoryKind kind, Instant at, PersonRef actor) implements HistoryEntry {
    }

    record Edited(UUID id, HistoryKind kind, Instant at, PersonRef actor, long version, List<FieldChange> changes)
            implements HistoryEntry {
    }

    record ResultChanged(UUID id, HistoryKind kind, Instant at, PersonRef actor, long version,
                         List<FieldChange> changes, UUID recordId, UUID problemId) implements HistoryEntry {
    }

    record DeliveryAcknowledged(UUID id, HistoryKind kind, Instant at, PersonRef actor, String via)
            implements HistoryEntry {
    }

    record DeliveryRetried(UUID id, HistoryKind kind, Instant at, PersonRef actor, List<FieldChange> changes)
            implements HistoryEntry {
    }

    record SendAttempt(UUID id, HistoryKind kind, Instant at, PersonRef actor, int attemptNo, String outcome,
                       Integer httpStatus, String error, Integer durationMs, Instant finishedAt)
            implements HistoryEntry {
    }

    record ResultReceived(UUID id, HistoryKind kind, Instant at, PersonRef actor, UUID recordId, String source,
                          String resultStatus, Integer sequence, String processingStatus, String reason,
                          int duplicates, Instant lastDuplicateAt, Instant receivedAt) implements HistoryEntry {
    }

    record Reconciliation(UUID id, HistoryKind kind, Instant at, PersonRef actor, String trigger, String outcome,
                          String details) implements HistoryEntry {
    }

    record ProblemOpened(UUID id, HistoryKind kind, Instant at, PersonRef actor, UUID problemId, String problemKind)
            implements HistoryEntry {
    }

    record ProblemResolved(UUID id, HistoryKind kind, Instant at, PersonRef actor, UUID problemId,
                           String problemKind, String decision, String comment) implements HistoryEntry {
    }

    /** One field before and after ({@code FieldChange}). */
    sealed interface FieldChange {

        String field();
    }

    record ValueChange(String field, String from, String to) implements FieldChange {
    }

    record AssigneeChange(String field, PersonRef from, PersonRef to) implements FieldChange {
    }
}
