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

    /** Counts a repeat of a known message; returns {@code false} if the message is new. */
    boolean countDuplicate(String messageId, Instant now) {
        return jdbc.sql("""
                        UPDATE inbox SET duplicate_count = duplicate_count + 1, last_duplicate_at = :now
                         WHERE message_id = :messageId""")
                .param("now", toSql(now))
                .param("messageId", messageId)
                .update() == 1;
    }

    /**
     * Stores a processed message. Returns {@code false} if a message with the same id was stored concurrently
     * (then the caller counts it as a duplicate).
     */
    boolean insert(UUID id, ResultMessage message, InboxSource source, String processingStatus, String reason,
                   Instant now) {
        return jdbc.sql("""
                        INSERT INTO inbox (id, message_id, feed_seq, operation_id, sequence, result_status, payload,
                                           source, processing_status, reason, received_at)
                        VALUES (:id, :messageId, :feedSeq, :operationId, :sequence, :status, CAST(:payload AS jsonb),
                                :source, :processingStatus, :reason, :now)
                        ON CONFLICT (message_id) WHERE message_id IS NOT NULL DO NOTHING""")
                .param("id", id)
                .param("messageId", message.messageId())
                .param("feedSeq", message.feedSeq())
                .param("operationId", message.operationId())
                .param("sequence", message.sequence())
                .param("status", message.status())
                .param("payload", JSON.writeValueAsString(message.raw()))
                .param("source", source.name())
                .param("processingStatus", processingStatus)
                .param("reason", reason)
                .param("now", toSql(now))
                .update() == 1;
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

    /** Content of the applied message with this sequence, if any (to tell a harmless repeat from a conflict). */
    Optional<String> appliedContent(UUID operationId, int sequence) {
        return jdbc.sql("""
                        SELECT payload FROM inbox
                         WHERE operation_id = :operationId AND sequence = :sequence AND processing_status = 'APPLIED'""")
                .param("operationId", operationId)
                .param("sequence", sequence)
                .query((rs, row) -> ResultMessage.parse(JSON.readTree(rs.getString("payload"))).content())
                .optional();
    }

    void applyToRequest(UUID requestId, ResultMessage message, Instant now) {
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
     * Records the applied sequence. A result also proves the carrier has the operation, so delivery that was not
     * confirmed yet becomes {@code ACKNOWLEDGED} (via RESULT) and any scheduled retry is cancelled.
     */
    void applyToOperation(UUID operationId, int sequence, Instant now) {
        jdbc.sql("""
                        UPDATE dispatch_operation
                           SET last_applied_seq = :sequence, updated_at = :now
                         WHERE operation_id = :operationId""")
                .param("sequence", sequence)
                .param("now", toSql(now))
                .param("operationId", operationId)
                .update();
        jdbc.sql("""
                        UPDATE dispatch_operation
                           SET delivery_status = 'ACKNOWLEDGED', acknowledged_at = :now, acknowledged_via = 'RESULT',
                               locked_until = NULL, last_error = NULL, updated_at = :now
                         WHERE operation_id = :operationId
                           AND delivery_status IN ('PENDING', 'SENDING', 'RETRY_WAIT', 'UNKNOWN', 'FAILED')""")
                .param("now", toSql(now))
                .param("operationId", operationId)
                .update();
    }

    record Target(UUID requestId, int lastAppliedSequence, String businessStatus) {

        boolean isFinal() {
            return "SUCCEEDED".equals(businessStatus) || "REJECTED".equals(businessStatus);
        }
    }
}
