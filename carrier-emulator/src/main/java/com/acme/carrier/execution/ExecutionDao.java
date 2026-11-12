package com.acme.carrier.execution;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
                        SELECT operation_id, weight_kg, delivery_address
                          FROM operation
                         WHERE state = 'ACCEPTED'
                         ORDER BY received_at, operation_id
                         LIMIT :limit
                         FOR UPDATE SKIP LOCKED
                        """)
                .param("limit", limit)
                .query((rs, row) -> new Pending(rs.getObject("operation_id", UUID.class),
                        rs.getBigDecimal("weight_kg"), rs.getString("delivery_address")))
                .list();
    }

    /** {@code ACCEPTED → EXECUTED}; returns {@code false} if the operation was not ACCEPTED (never executes twice). */
    boolean markExecuted(UUID operationId, Outcome outcome, Instant now) {
        return jdbc.sql("""
                        UPDATE operation
                           SET state = 'EXECUTED', execution_count = execution_count + 1, executed_at = :now,
                               result_status = :status, tracking_number = :tracking, price = :price, reason = :reason
                         WHERE operation_id = :id AND state = 'ACCEPTED'
                        """)
                .param("id", operationId)
                .param("now", Timestamp.from(now))
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

    record Pending(UUID operationId, BigDecimal weightKg, String deliveryAddress) {
    }
}
