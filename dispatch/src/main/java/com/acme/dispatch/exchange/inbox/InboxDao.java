package com.acme.dispatch.exchange.inbox;

import static com.acme.dispatch.common.SqlTime.toSql;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for the result inbox and for applying a result to the request and its operation. */
@Repository
public class InboxDao {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;

    public InboxDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Counts a repeat of a message known from this source; returns {@code false} if the message is new. */
    boolean countDuplicate(InboxSource source, String messageId, Instant now) {
        return jdbc.sql("""
                        UPDATE inbox SET duplicate_count = duplicate_count + 1, last_duplicate_at = :now
                         WHERE source = :source AND message_id = :messageId""")
                .param("now", toSql(now))
                .param("source", source.name())
                .param("messageId", messageId)
                .update() == 1;
    }

    /** The record of this message from this source, if it was received. */
    public Optional<UUID> findId(InboxSource source, String messageId) {
        return jdbc.sql("SELECT id FROM inbox WHERE source = :source AND message_id = :messageId")
                .param("source", source.name())
                .param("messageId", messageId)
                .query(UUID.class)
                .optional();
    }

    /**
     * Stores a received record, due for processing at once. Returns {@code false} if a record with the same message
     * id from the same source was stored concurrently (then the caller counts it as a duplicate). A repeated feed
     * position is not a duplicate but a broken invariant, so it fails ({@code inbox_feed_seq_unique}).
     */
    boolean insertReceived(UUID id, String messageId, Long feedSeq, String payload, InboxSource source, Instant now) {
        return jdbc.sql("""
                        INSERT INTO inbox (id, message_id, feed_seq, payload, source, processing_status, attempts,
                                           next_attempt_at, received_at)
                        VALUES (:id, :messageId, :feedSeq, :payload, :source, 'RECEIVED', 0, :now, :now)
                        ON CONFLICT (source, message_id) WHERE message_id IS NOT NULL DO NOTHING""")
                .param("id", id)
                .param("messageId", messageId)
                .param("feedSeq", feedSeq)
                .param("payload", payload)
                .param("source", source.name())
                .param("now", toSql(now))
                .update() == 1;
    }

    /**
     * The oldest received record due for processing, locked until the transaction ends; records locked by another
     * processor are skipped.
     */
    Optional<Pending> claimNextDue(Instant now) {
        return jdbc.sql("""
                        SELECT id, message_id, payload, attempts FROM inbox
                         WHERE processing_status = 'RECEIVED' AND next_attempt_at <= :now
                         ORDER BY received_at, feed_seq NULLS LAST, id
                         LIMIT 1
                           FOR UPDATE SKIP LOCKED""")
                .param("now", toSql(now))
                .query((rs, row) -> new Pending(rs.getObject("id", UUID.class), rs.getString("message_id"),
                        rs.getString("payload"), rs.getInt("attempts")))
                .optional();
    }

    /**
     * Records how a record was decided. The parsed fields are stored only when they are safe to store: typed values,
     * and the result status only if it is a known one.
     */
    void recordOutcome(UUID id, Decision decision, String reason, ResultMessage message) {
        jdbc.sql("""
                        UPDATE inbox
                           SET processing_status = :status, reason = :reason, next_attempt_at = NULL,
                               operation_id = :operationId, sequence = :sequence, result_status = :resultStatus
                         WHERE id = :id""")
                .param("status", decision.name())
                .param("reason", reason)
                .param("operationId", message == null ? null : message.operationId())
                .param("sequence", message == null ? null : message.sequence())
                .param("resultStatus", message == null || message.status() == null
                        || !ResultMessage.STATUSES.contains(message.status()) ? null : message.status())
                .param("id", id)
                .update();
    }

    /** A temporary failure: the record stays {@code RECEIVED} and is due again at {@code nextAttemptAt}. */
    void recordRetry(UUID id, int attempts, Instant nextAttemptAt, String error) {
        jdbc.sql("""
                        UPDATE inbox SET attempts = :attempts, next_attempt_at = :next, last_error = :error
                         WHERE id = :id""")
                .param("attempts", attempts)
                .param("next", toSql(nextAttemptAt))
                .param("error", error)
                .param("id", id)
                .update();
    }

    /** Processing kept failing: the record is {@code FAILED} and kept for review. */
    void recordFailed(UUID id, int attempts, String reason, String error) {
        jdbc.sql("""
                        UPDATE inbox
                           SET processing_status = 'FAILED', attempts = :attempts, next_attempt_at = NULL,
                               reason = :reason, last_error = :error
                         WHERE id = :id""")
                .param("attempts", attempts)
                .param("reason", reason)
                .param("error", error)
                .param("id", id)
                .update();
    }

    /** The operation and its request's state, with the operation row locked until the transaction ends. */
    Optional<Target> lockTarget(UUID operationId) {
        return jdbc.sql("""
                        SELECT o.request_id, o.last_applied_seq, r.business_status
                          FROM dispatch_operation o
                          JOIN delivery_request r ON r.id = o.request_id
                         WHERE o.operation_id = :operationId
                           FOR UPDATE OF o""")
                .param("operationId", operationId)
                .query((rs, row) -> new Target(rs.getObject("request_id", UUID.class), rs.getInt("last_applied_seq"),
                        rs.getString("business_status")))
                .optional();
    }

    /**
     * Content of the record applied to the request now ({@code applied_inbox_id}), if it has this sequence: to tell a
     * harmless repeat from a conflict. The applied record is the one processing applied last, or the one an operator
     * chose in a conflict; a smaller sequence is stale whatever its content.
     */
    Optional<String> appliedContent(UUID operationId, int sequence) {
        return jdbc.sql("""
                        SELECT i.payload FROM dispatch_operation o
                          JOIN inbox i ON i.id = o.applied_inbox_id
                         WHERE o.operation_id = :operationId AND i.sequence = :sequence""")
                .param("operationId", operationId)
                .param("sequence", sequence)
                .query((rs, row) -> ResultMessage.parse(JSON.readTree(rs.getString("payload"))).content())
                .optional();
    }

    /** Writes a result to the request: business status and result fields, {@code version + 1}. */
    public void applyToRequest(UUID requestId, ResultMessage message, Instant now) {
        BigDecimal price = "SUCCEEDED".equals(message.status()) ? message.price() : null;
        jdbc.sql("""
                        UPDATE delivery_request
                           SET business_status = :status,
                               tracking_number = :tracking, price = :price, rejection_reason = :reason,
                               result_at = :resultAt,
                               version = version + 1, updated_at = :now
                         WHERE id = :requestId""")
                .param("status", message.status())
                .param("tracking", "SUCCEEDED".equals(message.status()) ? message.trackingNumber() : null)
                .param("price", price)
                .param("reason", "REJECTED".equals(message.status()) ? message.reason() : null)
                .param("resultAt", message.isFinal() ? toSql(message.occurredAt()) : null)
                .param("now", toSql(now))
                .param("requestId", requestId)
                .update();
    }

    /**
     * Records the applied sequence and the applied record. A result also proves the carrier has the operation, so
     * delivery that was not confirmed yet becomes {@code ACKNOWLEDGED} (via RESULT) and any scheduled retry is
     * cancelled. Returns whether it confirmed the delivery just now.
     */
    boolean applyToOperation(UUID operationId, int sequence, UUID inboxId, Instant now) {
        jdbc.sql("""
                        UPDATE dispatch_operation
                           SET last_applied_seq = :sequence, applied_inbox_id = :inboxId, updated_at = :now
                         WHERE operation_id = :operationId""")
                .param("sequence", sequence)
                .param("inboxId", inboxId)
                .param("now", toSql(now))
                .param("operationId", operationId)
                .update();
        return jdbc.sql("""
                        UPDATE dispatch_operation
                           SET delivery_status = 'ACKNOWLEDGED', acknowledged_at = :now, acknowledged_via = 'RESULT',
                               locked_until = NULL, last_error = NULL, updated_at = :now
                         WHERE operation_id = :operationId
                           AND delivery_status IN ('PENDING', 'SENDING', 'RETRY_WAIT', 'UNKNOWN', 'FAILED')""")
                .param("now", toSql(now))
                .param("operationId", operationId)
                .update() == 1;
    }

    /** The raw text of a record. */
    String payloadOf(UUID inboxId) {
        return jdbc.sql("SELECT payload FROM inbox WHERE id = :id")
                .param("id", inboxId)
                .query(String.class)
                .single();
    }

    /**
     * An operator chose this record: it becomes the applied one; the applied sequence never goes back. Delivery is
     * already confirmed (results of the operation were applied before).
     */
    void makeApplied(UUID operationId, int sequence, UUID inboxId, Instant now) {
        jdbc.sql("""
                        UPDATE dispatch_operation
                           SET last_applied_seq = GREATEST(last_applied_seq, :sequence), applied_inbox_id = :inboxId,
                               updated_at = :now
                         WHERE operation_id = :operationId""")
                .param("sequence", sequence)
                .param("inboxId", inboxId)
                .param("now", toSql(now))
                .param("operationId", operationId)
                .update();
    }

    /** The request of an operation, if the operation is known; nothing is locked. */
    Optional<UUID> requestOf(UUID operationId) {
        return jdbc.sql("SELECT request_id FROM dispatch_operation WHERE operation_id = :operationId")
                .param("operationId", operationId)
                .query(UUID.class)
                .optional();
    }

    /** How a processed record was decided; stored as {@code inbox.processing_status}. */
    public enum Decision {
        APPLIED,
        STALE,
        CONFLICT,
        INVALID,
        /** Processing kept failing; recorded only by {@link #recordFailed}. */
        FAILED
    }

    /** A received record claimed for processing. */
    record Pending(UUID id, String messageId, String payload, int attempts) {
    }

    record Target(UUID requestId, int lastAppliedSequence, String businessStatus) {

        boolean isFinal() {
            return "SUCCEEDED".equals(businessStatus) || "REJECTED".equals(businessStatus);
        }
    }
}
