package com.acme.carrier.execution;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.acme.carrier.fault.FaultSnapshot;
import com.acme.carrier.operation.ResultStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class ExecutionDao {

    private static final Logger log = LoggerFactory.getLogger(ExecutionDao.class);

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
     * if the operation was not ACCEPTED (never executes twice).
     */
    boolean markExecuted(UUID operationId, Outcome outcome, MessageIds messageIds, Instant now) {
        return jdbc.sql("""
                        UPDATE operation
                           SET state = 'EXECUTED', executed_at = :now,
                               result_status = :status, tracking_number = :tracking, price = :price, reason = :reason,
                               in_progress_message_id = :inProgressId, result_message_id = :resultId
                         WHERE operation_id = :id AND state = 'ACCEPTED'
                        """)
                .param("id", operationId)
                .param("now", Timestamp.from(now))
                .param("inProgressId", messageIds.inProgress())
                .param("resultId", messageIds.result())
                .param("status", outcome.status().name())
                .param("tracking", outcome.trackingNumber())
                .param("price", outcome.price())
                .param("reason", outcome.reason())
                .update() == 1;
    }

    /**
     * Publishes a feed entry. Every feed publication (immediate or deferred) goes through here.
     * <p>
     * For an operation that remembered the {@code duplicate} fault mode the entry is inserted twice: the same
     * {@code messageId} and content under adjacent {@code feedSeq} (the executor is the only feed writer),
     * in the caller's transaction.
     */
    void publish(FeedEntry entry, boolean duplicate) {
        insert(entry);
        if (duplicate) {
            insert(entry);
            log.warn("Feed entry published twice (duplicate fault): operationId={}, sequence={}, messageId={}",
                    entry.operationId(), entry.sequence(), entry.messageId());
        }
    }

    private void insert(FeedEntry entry) {
        jdbc.sql("""
                        INSERT INTO feed_entry (message_id, operation_id, sequence, status, tracking_number, price,
                                                reason, occurred_at)
                        VALUES (:messageId, :operationId, :sequence, :status, :tracking, :price, :reason, :occurredAt)
                        """)
                .param("messageId", entry.messageId())
                .param("operationId", entry.operationId())
                .param("sequence", entry.sequence())
                .param("status", entry.status().name())
                .param("tracking", entry.trackingNumber())
                .param("price", entry.price())
                .param("reason", entry.reason())
                .param("occurredAt", Timestamp.from(entry.occurredAt()))
                .update();
    }

    record Pending(UUID operationId, BigDecimal weightKg, String deliveryAddress, FaultSnapshot faults) {
    }

    /** Message ids of an operation's feed entries, assigned at execution. */
    record MessageIds(UUID inProgress, UUID result) {
    }

    /** A feed entry to publish ({@code feed_entry} without {@code feed_seq}, which the database assigns). */
    record FeedEntry(UUID messageId, UUID operationId, int sequence, ResultStatus status, String trackingNumber,
                     BigDecimal price, String reason, Instant occurredAt) {
    }
}
