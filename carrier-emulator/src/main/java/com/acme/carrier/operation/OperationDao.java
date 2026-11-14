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

import com.acme.carrier.fault.FaultSnapshot;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OperationDao {

    /**
     * Operation columns. The final result is read from the operation row (its message id is assigned at
     * execution), so it is visible before a delayed publication and matches the feed entry published later.
     */
    private static final String SELECT_OPERATION = """
            SELECT o.operation_id, o.cargo_description, o.weight_kg, o.delivery_address, o.deadline, o.state,
                   o.received_at, o.executed_at, o.received_count, o.fault_duplicate, o.fault_delayed,
                   o.result_publish_at, o.result_message_id, o.result_status, o.tracking_number, o.price, o.reason
              FROM operation o
            """;

    /** Sequence of the final result; {@code IN_PROGRESS} is 1. */
    public static final int FINAL_SEQUENCE = 2;

    private final JdbcClient jdbc;

    public OperationDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Stores a new operation; returns {@code false} if the operationId is already known. */
    boolean insertIfAbsent(SubmitOperationRequest request, String payloadHash, FaultSnapshot faults, Instant now) {
        int inserted = jdbc.sql("""
                        INSERT INTO operation (operation_id, cargo_description, weight_kg, delivery_address, deadline,
                                               payload_hash, state, received_count, received_at,
                                               fault_duplicate, fault_delayed)
                        VALUES (:id, :cargo, :weight, :address, :deadline, :hash, 'ACCEPTED', 1, :now,
                                :duplicate, :delayed)
                        ON CONFLICT (operation_id) DO NOTHING
                        """)
                .param("duplicate", faults.duplicate())
                .param("delayed", faults.delayed())
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
        return jdbc.sql(SELECT_OPERATION + " WHERE o.operation_id = :id")
                .param("id", operationId)
                .query(OperationDao::mapOperation)
                .optional();
    }

    /** Newest first, at most {@code limit}. */
    public List<AdminOperation> listNewest(int limit) {
        return jdbc.sql(SELECT_OPERATION + " ORDER BY o.received_at DESC, o.operation_id DESC LIMIT :limit")
                .param("limit", limit)
                .query(OperationDao::mapOperation)
                .list();
    }

    private static AdminOperation mapOperation(ResultSet rs, int row) throws SQLException {
        UUID operationId = rs.getObject("operation_id", UUID.class);
        OperationStateValue state = OperationStateValue.valueOf(rs.getString("state"));
        Instant executedAt = instant(rs, "executed_at");
        OperationResult result = null;
        if (state == OperationStateValue.EXECUTED) {
            // The final result occurs at execution, whenever it is published.
            result = new OperationResult(rs.getObject("result_message_id", UUID.class), operationId,
                    FINAL_SEQUENCE, ResultStatus.valueOf(rs.getString("result_status")),
                    rs.getString("tracking_number"), rs.getBigDecimal("price"), rs.getString("reason"), executedAt);
        }
        FaultSnapshot faults = new FaultSnapshot(rs.getBoolean("fault_duplicate"), rs.getBoolean("fault_delayed"));
        BigDecimal weight = rs.getBigDecimal("weight_kg");
        return new AdminOperation(operationId,
                rs.getString("cargo_description"),
                SubmitOperationRequest.canonicalWeight(weight),
                rs.getString("delivery_address"),
                rs.getObject("deadline", LocalDate.class),
                state,
                instant(rs, "received_at"),
                executedAt,
                result,
                rs.getInt("received_count"),
                state == OperationStateValue.EXECUTED ? 1 : 0,
                faults.names(),
                instant(rs, "result_publish_at"));
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
