package com.acme.dispatch.exchange.reconcile;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.acme.dispatch.exchange.ResultSnapshot;
import com.acme.dispatch.request.PersonRef;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Read-side SQL of reconciliation checks; the caller has checked the request's scope. */
@Repository
public class ReconciliationQueryDao {

    private static final String SELECT = """
            SELECT c.id, c.checked_at, c.run_type, c.performed_by, u.display_name AS performed_by_name, c.outcome,
                   c.acknowledged, c.local_status, c.local_tracking_number, c.local_price, c.local_reason,
                   c.carrier_state, c.carrier_result_status, c.carrier_tracking_number,
                   c.carrier_price, c.carrier_reason, c.carrier_sequence, c.carrier_message_id,
                   c.carrier_occurred_at, c.details
              FROM reconciliation c
              LEFT JOIN app_user u ON u.id = c.performed_by
            """;

    private final JdbcClient jdbc;

    public ReconciliationQueryDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The latest checks of a request, newest first. */
    public List<ReconciliationView> forRequest(UUID requestId, int limit) {
        return jdbc.sql(SELECT + " WHERE c.request_id = :requestId ORDER BY c.checked_at DESC, c.id DESC LIMIT :limit")
                .param("requestId", requestId)
                .param("limit", limit)
                .query((rs, row) -> map(rs))
                .list();
    }

    /** One check. */
    public Optional<ReconciliationView> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE c.id = :id")
                .param("id", id)
                .query((rs, row) -> map(rs))
                .optional();
    }

    private static ReconciliationView map(ResultSet rs) throws SQLException {
        UUID performedBy = rs.getObject("performed_by", UUID.class);
        String resultStatus = rs.getString("carrier_result_status");
        String localStatus = rs.getString("local_status");
        return new ReconciliationView(
                rs.getObject("id", UUID.class),
                instant(rs, "checked_at"),
                RunType.valueOf(rs.getString("run_type")),
                performedBy == null ? null : new PersonRef(performedBy, rs.getString("performed_by_name")),
                ReconciliationOutcome.valueOf(rs.getString("outcome")),
                rs.getBoolean("acknowledged"),
                localStatus == null ? null : new ResultSnapshot(localStatus, rs.getString("local_tracking_number"),
                        rs.getBigDecimal("local_price"), rs.getString("local_reason")),
                rs.getString("carrier_state"),
                resultStatus == null ? null : new ReconciliationView.CarrierResult(resultStatus,
                        rs.getString("carrier_tracking_number"), rs.getBigDecimal("carrier_price"),
                        rs.getString("carrier_reason"), rs.getInt("carrier_sequence"),
                        rs.getString("carrier_message_id"), instant(rs, "carrier_occurred_at")),
                rs.getString("details"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
