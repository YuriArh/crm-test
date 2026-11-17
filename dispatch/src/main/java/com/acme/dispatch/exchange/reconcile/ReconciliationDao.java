package com.acme.dispatch.exchange.reconcile;

import static com.acme.dispatch.common.SqlTime.toSql;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import com.acme.dispatch.exchange.DeliveryStatus;
import com.acme.dispatch.exchange.inbox.ResultMessage;
import com.acme.dispatch.exchange.reconcile.ReconciliationRules.Signature;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL of reconciliation: the operation's state under its row lock, the checks, and the operation's schedule. */
@Repository
public class ReconciliationDao {

    private final JdbcClient jdbc;

    public ReconciliationDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The operation of a request, without locking: what to ask the carrier about. */
    public Optional<UUID> operationOf(UUID requestId) {
        return jdbc.sql("SELECT operation_id FROM dispatch_operation WHERE request_id = :requestId")
                .param("requestId", requestId)
                .query(UUID.class)
                .optional();
    }

    /**
     * Our state of the request, its operation row locked until the transaction ends — the same row the result handler
     * locks, so a check never races with processing.
     */
    Optional<State> lock(UUID requestId) {
        return jdbc.sql("""
                        SELECT o.operation_id, o.delivery_status, o.reconciled_at, o.next_reconcile_at,
                               r.business_status, r.tracking_number, r.price, r.rejection_reason
                          FROM dispatch_operation o
                          JOIN delivery_request r ON r.id = o.request_id
                         WHERE o.request_id = :requestId
                           FOR UPDATE OF o""")
                .param("requestId", requestId)
                .query((rs, row) -> new State(rs.getObject("operation_id", UUID.class),
                        DeliveryStatus.valueOf(rs.getString("delivery_status")),
                        instant(rs, "reconciled_at"), instant(rs, "next_reconcile_at"),
                        ReconciliationRules.localContent(rs.getString("business_status"),
                                rs.getString("tracking_number"), rs.getBigDecimal("price"),
                                rs.getString("rejection_reason"))))
                .optional();
    }

    /** What the newest check of the request found. */
    Optional<Signature> lastSignature(UUID requestId) {
        return jdbc.sql("""
                        SELECT outcome, carrier_state, carrier_result_status, delivery_status FROM reconciliation
                         WHERE request_id = :requestId
                         ORDER BY checked_at DESC, id DESC
                         LIMIT 1""")
                .param("requestId", requestId)
                .query((rs, row) -> new Signature(ReconciliationOutcome.valueOf(rs.getString("outcome")),
                        rs.getString("carrier_state"), rs.getString("carrier_result_status"),
                        DeliveryStatus.valueOf(rs.getString("delivery_status"))))
                .optional();
    }

    /** Records a check. */
    void insert(Row row) {
        ResultMessage result = row.carrierResult();
        jdbc.sql("""
                        INSERT INTO reconciliation (id, request_id, operation_id, checked_at, run_type, performed_by,
                            outcome, acknowledged, delivery_status, local_result, carrier_state, carrier_result_status,
                            carrier_tracking_number, carrier_price, carrier_reason, carrier_sequence,
                            carrier_message_id, carrier_occurred_at, carrier_response, details, inbox_id)
                        VALUES (:id, :requestId, :operationId, :checkedAt, :runType, :performedBy, :outcome,
                            :acknowledged, :delivery, :localResult, :carrierState, :resultStatus, :tracking, :price,
                            :reason, :sequence, :messageId, :occurredAt, :response, :details, :inboxId)""")
                .param("id", row.id())
                .param("requestId", row.requestId())
                .param("operationId", row.operationId())
                .param("checkedAt", toSql(row.checkedAt()))
                .param("runType", row.runType().name())
                .param("performedBy", row.performedBy())
                .param("outcome", row.outcome().name())
                .param("acknowledged", row.acknowledged())
                .param("delivery", row.delivery().name())
                .param("localResult", row.localResult())
                .param("carrierState", row.carrierState())
                .param("resultStatus", result == null ? null : result.status())
                .param("tracking", result == null ? null : result.trackingNumber())
                .param("price", result == null ? null : result.price())
                .param("reason", result == null ? null : result.reason())
                .param("sequence", result == null ? null : result.sequence())
                .param("messageId", result == null ? null : result.messageId())
                .param("occurredAt", result == null ? null : toSql(result.occurredAt()))
                .param("response", row.carrierResponse())
                .param("details", row.details())
                .param("inboxId", row.inboxId())
                .update();
    }

    /** When the operation was checked and when the background checks it next. */
    void schedule(UUID requestId, Instant reconciledAt, Instant next) {
        jdbc.sql("""
                        UPDATE dispatch_operation SET reconciled_at = :reconciledAt, next_reconcile_at = :next
                         WHERE request_id = :requestId""")
                .param("reconciledAt", toSql(reconciledAt))
                .param("next", toSql(next))
                .param("requestId", requestId)
                .update();
    }

    /**
     * The carrier knows the operation: delivery is confirmed by reconciliation, a planned retry is cancelled. Like
     * the confirmation by a result, conditional on a status it may leave; a sender's later outcome no longer applies
     * ({@code delivery_status = 'SENDING' AND attempts = N}). Returns whether it took effect.
     */
    boolean acknowledge(UUID requestId, Instant now) {
        return jdbc.sql("""
                        UPDATE dispatch_operation
                           SET delivery_status = 'ACKNOWLEDGED', acknowledged_at = :now,
                               acknowledged_via = 'RECONCILIATION', locked_until = NULL, last_error = NULL,
                               updated_at = :now
                         WHERE request_id = :requestId
                           AND delivery_status IN ('PENDING', 'SENDING', 'RETRY_WAIT', 'UNKNOWN', 'FAILED')""")
                .param("now", toSql(now))
                .param("requestId", requestId)
                .update() == 1;
    }

    /** The carrier does not know an UNKNOWN delivery: send it again now, within its retry budget. */
    boolean sendNow(UUID requestId, Instant now) {
        return jdbc.sql("""
                        UPDATE dispatch_operation SET next_attempt_at = :now, updated_at = :now
                         WHERE request_id = :requestId AND delivery_status = 'UNKNOWN'""")
                .param("now", toSql(now))
                .param("requestId", requestId)
                .update() == 1;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /**
     * Our side of a request under the lock.
     *
     * @param localResult our final result in canonical form, or {@code null}
     */
    record State(UUID operationId, DeliveryStatus delivery, Instant reconciledAt, Instant nextReconcileAt,
                 String localResult) {
    }

    /** One recorded check. */
    record Row(UUID id, UUID requestId, UUID operationId, Instant checkedAt, RunType runType, UUID performedBy,
               ReconciliationOutcome outcome, boolean acknowledged, DeliveryStatus delivery, String localResult,
               String carrierState, ResultMessage carrierResult, String carrierResponse, String details,
               UUID inboxId) {
    }
}
