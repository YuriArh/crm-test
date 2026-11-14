package com.acme.carrier.execution;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.acme.carrier.fault.FaultSnapshot;
import com.acme.carrier.operation.ResultStatus;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class ExecutionDao {

    private final JdbcClient jdbc;

    ExecutionDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Oldest accepted operations, locked for this transaction; rows locked by someone else are skipped. */
    List<Pending> lockAccepted(int limit) {
        return jdbc.sql("""
                        SELECT operation_id, weight_kg, delivery_address, fault_duplicate, fault_delayed
                          FROM operation
                         WHERE state = 'ACCEPTED'
                         ORDER BY received_at, operation_id
                         LIMIT :limit
                         FOR UPDATE SKIP LOCKED
                        """)
                .param("limit", limit)
                .query((rs, row) -> new Pending(rs.getObject("operation_id", UUID.class),
                        rs.getBigDecimal("weight_kg"), rs.getString("delivery_address"),
                        new FaultSnapshot(rs.getBoolean("fault_duplicate"), rs.getBoolean("fault_delayed"))))
                .list();
    }

    /**
     * {@code ACCEPTED → EXECUTED} with the outcome and the message ids of both feed entries; returns {@code false}
     * if the operation was not ACCEPTED (never executes twice). A non-null {@code publishAt} defers the feed
     * entries ({@code delayed}) until {@link #lockDuePublications} returns the operation.
     */
    boolean markExecuted(UUID operationId, Outcome outcome, MessageIds messageIds, Instant now, Instant publishAt) {
        return jdbc.sql("""
                        UPDATE operation
                           SET state = 'EXECUTED', executed_at = :now,
                               result_status = :status, tracking_number = :tracking, price = :price, reason = :reason,
                               in_progress_message_id = :inProgressId, result_message_id = :resultId,
                               result_publish_at = :publishAt
                         WHERE operation_id = :id AND state = 'ACCEPTED'
                        """)
                .param("id", operationId)
                .param("now", Timestamp.from(now))
                .param("publishAt", publishAt == null ? null : Timestamp.from(publishAt), Types.TIMESTAMP)
                .param("inProgressId", messageIds.inProgress())
                .param("resultId", messageIds.result())
                .param("status", outcome.status().name())
                .param("tracking", outcome.trackingNumber())
                .param("price", outcome.price())
                .param("reason", outcome.reason())
                .update() == 1;
    }

    void publish(UUID messageId, UUID operationId, int sequence, ResultStatus status, String trackingNumber,
                 BigDecimal price, String reason, Instant occurredAt) {
        jdbc.sql("""
                        INSERT INTO feed_entry (message_id, operation_id, sequence, status, tracking_number, price,
                                                reason, occurred_at)
                        VALUES (:messageId, :operationId, :sequence, :status, :tracking, :price, :reason, :occurredAt)
                        """)
                .param("messageId", messageId)
                .param("operationId", operationId)
                .param("sequence", sequence)
                .param("status", status.name())
                .param("tracking", trackingNumber)
                .param("price", price)
                .param("reason", reason)
                .param("occurredAt", Timestamp.from(occurredAt))
                .update();
    }

    /**
     * Executed operations whose deferred feed entries are due ({@code result_publish_at <= now}), oldest first,
     * locked for this transaction; rows locked by someone else are skipped.
     */
    List<DuePublication> lockDuePublications(Instant now, int limit) {
        return jdbc.sql("""
                        SELECT operation_id, executed_at, result_publish_at, in_progress_message_id,
                               result_message_id, result_status, tracking_number, price, reason,
                               fault_duplicate, fault_delayed
                          FROM operation
                         WHERE result_publish_at <= :now
                         ORDER BY result_publish_at, operation_id
                         LIMIT :limit
                         FOR UPDATE SKIP LOCKED
                        """)
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .query((rs, row) -> new DuePublication(rs.getObject("operation_id", UUID.class),
                        rs.getTimestamp("executed_at").toInstant(),
                        rs.getTimestamp("result_publish_at").toInstant(),
                        new MessageIds(rs.getObject("in_progress_message_id", UUID.class),
                                rs.getObject("result_message_id", UUID.class)),
                        new Outcome(ResultStatus.valueOf(rs.getString("result_status")),
                                rs.getString("tracking_number"), rs.getBigDecimal("price"), rs.getString("reason")),
                        new FaultSnapshot(rs.getBoolean("fault_duplicate"), rs.getBoolean("fault_delayed"))))
                .list();
    }

    /** Marks the deferred feed entries as published; returns {@code false} if nothing was waiting. */
    boolean clearPublishAt(UUID operationId) {
        return jdbc.sql("""
                        UPDATE operation SET result_publish_at = NULL
                         WHERE operation_id = :id AND result_publish_at IS NOT NULL
                        """)
                .param("id", operationId)
                .update() == 1;
    }

    record Pending(UUID operationId, BigDecimal weightKg, String deliveryAddress, FaultSnapshot faults) {
    }

    /** An executed operation with deferred feed entries; the entries are rebuilt from the operation row. */
    record DuePublication(UUID operationId, Instant executedAt, Instant publishAt, MessageIds messageIds,
                          Outcome outcome, FaultSnapshot faults) {
    }

    /** Message ids of an operation's feed entries, assigned at execution. */
    record MessageIds(UUID inProgress, UUID result) {
    }
}
