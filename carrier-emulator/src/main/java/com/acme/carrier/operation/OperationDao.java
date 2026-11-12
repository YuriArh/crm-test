package com.acme.carrier.operation;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OperationDao {

    /** Operation columns plus its latest published result (highest sequence, first published copy). */
    private static final String SELECT_WITH_RESULT = """
            SELECT o.operation_id, o.cargo_description, o.weight_kg, o.delivery_address, o.deadline, o.state,
                   o.received_at, o.executed_at, o.received_count, o.execution_count,
                   r.message_id, r.sequence, r.status, r.tracking_number, r.price, r.reason, r.occurred_at
              FROM operation o
              LEFT JOIN LATERAL (
                   SELECT f.message_id, f.sequence, f.status, f.tracking_number, f.price, f.reason, f.occurred_at
                     FROM feed_entry f
                    WHERE f.operation_id = o.operation_id
                    ORDER BY f.sequence DESC, f.feed_seq
                    LIMIT 1) r ON true
            """;

    private final JdbcClient jdbc;

    public OperationDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Stores a new operation; returns {@code false} if the operationId is already known. */
    boolean insertIfAbsent(SubmitOperationRequest request, String payloadHash, Instant now) {
        int inserted = jdbc.sql("""
                        INSERT INTO operation (operation_id, cargo_description, weight_kg, delivery_address, deadline,
                                               payload_hash, state, received_count, execution_count, received_at)
                        VALUES (:id, :cargo, :weight, :address, :deadline, :hash, 'ACCEPTED', 1, 0, :now)
                        ON CONFLICT (operation_id) DO NOTHING
                        """)
                .param("id", request.operationId())
                .param("cargo", request.cargoDescription())
                .param("weight", request.weightKg())
                .param("address", request.deliveryAddress())
                .param("deadline", request.deadline())
                .param("hash", payloadHash)
                .param("now", Timestamp.from(now))
                .update();
        return inserted == 1;
    }

    /** Counts a repeat of a known operation; returns {@code false} if its stored content differs. */
    boolean registerRepeat(UUID operationId, String payloadHash) {
        return jdbc.sql("""
                        UPDATE operation SET received_count = received_count + 1
                         WHERE operation_id = :id AND payload_hash = :hash
                        """)
                .param("id", operationId)
                .param("hash", payloadHash)
                .update() == 1;
    }

    public Optional<AdminOperation> find(UUID operationId) {
        return jdbc.sql(SELECT_WITH_RESULT + " WHERE o.operation_id = :id")
                .param("id", operationId)
                .query(OperationDao::mapOperation)
                .optional();
    }

    /** Newest first, at most {@code limit}. */
    public List<AdminOperation> listNewest(int limit) {
        return jdbc.sql(SELECT_WITH_RESULT + " ORDER BY o.received_at DESC, o.operation_id DESC LIMIT :limit")
                .param("limit", limit)
                .query(OperationDao::mapOperation)
                .list();
    }

    private static AdminOperation mapOperation(ResultSet rs, int row) throws SQLException {
        UUID operationId = rs.getObject("operation_id", UUID.class);
        OperationResult result = null;
        UUID messageId = rs.getObject("message_id", UUID.class);
        if (messageId != null) {
            result = new OperationResult(messageId, operationId, rs.getInt("sequence"),
                    ResultStatus.valueOf(rs.getString("status")), rs.getString("tracking_number"),
                    rs.getBigDecimal("price"), rs.getString("reason"), instant(rs, "occurred_at"));
        }
        BigDecimal weight = rs.getBigDecimal("weight_kg");
        return new AdminOperation(operationId,
                rs.getString("cargo_description"),
                SubmitOperationRequest.canonicalWeight(weight),
                rs.getString("delivery_address"),
                rs.getObject("deadline", LocalDate.class),
                OperationStateValue.valueOf(rs.getString("state")),
                instant(rs, "received_at"),
                instant(rs, "executed_at"),
                result,
                rs.getInt("received_count"),
                rs.getInt("execution_count"));
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
