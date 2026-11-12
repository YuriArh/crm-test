package com.acme.dispatch.exchange;

import static com.acme.dispatch.common.SqlTime.instant;
import static com.acme.dispatch.common.SqlTime.toSql;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Explicit SQL for {@code dispatch_operation}. State changes go through conditional updates
 * ({@code WHERE delivery_status IN (...)}), never read-modify-save.
 */
@Repository
public class DispatchOperationDao {

    private final JdbcClient jdbc;

    public DispatchOperationDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Puts a new operation into the send queue; must run in the transaction that creates the request. */
    public void insertPending(UUID requestId, UUID operationId, String payloadHash, Instant now) {
        jdbc.sql("""
                        INSERT INTO dispatch_operation (request_id, operation_id, payload_hash, delivery_status,
                            next_attempt_at, created_at, updated_at)
                        VALUES (:requestId, :operationId, :payloadHash, 'PENDING', :now, :now, :now)""")
                .param("requestId", requestId)
                .param("operationId", operationId)
                .param("payloadHash", payloadHash)
                .param("now", toSql(now))
                .update();
    }

    public Optional<DispatchOperation> findByRequestId(UUID requestId) {
        return jdbc.sql("SELECT * FROM dispatch_operation WHERE request_id = :requestId")
                .param("requestId", requestId)
                .query(DispatchOperationDao::map)
                .optional();
    }

    static DispatchOperation map(ResultSet rs, int rowNum) throws SQLException {
        return new DispatchOperation(
                rs.getObject("request_id", UUID.class),
                rs.getObject("operation_id", UUID.class),
                rs.getString("payload_hash"),
                DeliveryStatus.valueOf(rs.getString("delivery_status")),
                rs.getInt("attempts"),
                instant(rs, "next_attempt_at"),
                instant(rs, "locked_until"),
                rs.getString("last_error"),
                instant(rs, "acknowledged_at"),
                rs.getString("acknowledged_via"),
                rs.getInt("last_applied_seq"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }
}
