package com.acme.dispatch.problem;

import static com.acme.dispatch.common.SqlTime.toSql;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import com.acme.dispatch.exchange.inbox.ResultMessage;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL over {@code problem}. Opening relies on the partial unique indexes: an open delivery problem of a kind, or a
 * problem of a record, is not opened twice. Closing is conditional on {@code status = 'OPEN'}.
 */
@Repository
public class ProblemDao {

    private final JdbcClient jdbc;

    public ProblemDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Opens a problem unless the same one is already open (delivery) or exists (record). Returns whether it did. */
    public boolean open(UUID id, ProblemKind kind, UUID requestId, UUID inboxId, String details, Instant now) {
        return jdbc.sql("""
                        INSERT INTO problem (id, kind, status, request_id, inbox_id, details, opened_at)
                        VALUES (:id, :kind, 'OPEN', :requestId, :inboxId, :details, :now)
                        ON CONFLICT DO NOTHING""")
                .param("id", id)
                .param("kind", kind.name())
                .param("requestId", requestId)
                .param("inboxId", inboxId)
                .param("details", details)
                .param("now", toSql(now))
                .update() == 1;
    }

    /** Opens a reconciliation conflict of a request, linked to the check that found it, unless one is open. */
    public boolean openReconciliationConflict(UUID id, UUID requestId, UUID reconciliationId, String details,
                                              Instant now) {
        return jdbc.sql("""
                        INSERT INTO problem (id, kind, status, request_id, reconciliation_id, details, opened_at)
                        VALUES (:id, 'RECONCILIATION_CONFLICT', 'OPEN', :requestId, :reconciliationId, :details, :now)
                        ON CONFLICT DO NOTHING""")
                .param("id", id)
                .param("requestId", requestId)
                .param("reconciliationId", reconciliationId)
                .param("details", details)
                .param("now", toSql(now))
                .update() == 1;
    }

    /**
     * Points the request's open reconciliation conflict at a newer check, so it shows the carrier's latest answer.
     * Returns whether one was open.
     */
    public boolean relinkOpenReconciliationConflict(UUID requestId, UUID reconciliationId, String details) {
        return jdbc.sql("""
                        UPDATE problem SET reconciliation_id = :reconciliationId, details = :details
                         WHERE request_id = :requestId AND kind = 'RECONCILIATION_CONFLICT' AND status = 'OPEN'""")
                .param("reconciliationId", reconciliationId)
                .param("details", details)
                .param("requestId", requestId)
                .update() == 1;
    }

    /** The request's newest reconciliation conflict with the answer it was about, if it ever had one. */
    public Optional<LastConflict> lastReconciliationConflict(UUID requestId) {
        return jdbc.sql("""
                        SELECT p.id, p.status, p.decision, r.carrier_result_status, r.carrier_tracking_number,
                               r.carrier_price, r.carrier_reason, r.local_result
                          FROM problem p
                          JOIN reconciliation r ON r.id = p.reconciliation_id
                         WHERE p.request_id = :requestId AND p.kind = 'RECONCILIATION_CONFLICT'
                         ORDER BY p.opened_at DESC, p.id DESC
                         LIMIT 1""")
                .param("requestId", requestId)
                .query((rs, row) -> new LastConflict(rs.getObject("id", UUID.class),
                        ProblemStatus.valueOf(rs.getString("status")),
                        rs.getString("decision") == null ? null : ProblemDecision.valueOf(rs.getString("decision")),
                        rs.getString("carrier_result_status") == null ? null
                                : ResultMessage.content(rs.getString("carrier_result_status"),
                                        rs.getString("carrier_tracking_number"), rs.getBigDecimal("carrier_price"),
                                        rs.getString("carrier_reason")),
                        rs.getString("local_result")))
                .optional();
    }

    /**
     * A reconciliation conflict and the two sides it was about.
     *
     * @param carrierContent the carrier's result in canonical form, or {@code null} if it had none
     * @param localResult    ours, in the same form
     */
    public record LastConflict(UUID id, ProblemStatus status, ProblemDecision decision, String carrierContent,
                               String localResult) {
    }

    /** Closes the request's open problem of this kind with an operator's decision. Returns whether it did. */
    public boolean closeByOperator(UUID requestId, ProblemKind kind, ProblemDecision decision, UUID operatorId,
                                   String comment, Instant now) {
        return jdbc.sql("""
                        UPDATE problem SET status = 'RESOLVED', resolved_at = :now, resolved_by = :operatorId,
                                           decision = :decision, comment = :comment
                         WHERE request_id = :requestId AND kind = :kind AND status = 'OPEN'""")
                .param("now", toSql(now))
                .param("operatorId", operatorId)
                .param("decision", decision.name())
                .param("comment", comment)
                .param("requestId", requestId)
                .param("kind", kind.name())
                .update() == 1;
    }

    /** Closes one open problem with an operator's decision. Returns whether it was open. */
    public boolean closeById(UUID problemId, ProblemDecision decision, UUID operatorId, String comment, Instant now) {
        return jdbc.sql("""
                        UPDATE problem SET status = 'RESOLVED', resolved_at = :now, resolved_by = :operatorId,
                                           decision = :decision, comment = :comment
                         WHERE id = :id AND status = 'OPEN'""")
                .param("now", toSql(now))
                .param("operatorId", operatorId)
                .param("decision", decision.name())
                .param("comment", comment)
                .param("id", problemId)
                .update() == 1;
    }

    /** The record a problem is about, if it is a record problem. */
    public Optional<UUID> recordOf(UUID problemId) {
        return jdbc.sql("SELECT inbox_id FROM problem WHERE id = :id AND inbox_id IS NOT NULL")
                .param("id", problemId)
                .query(UUID.class)
                .optional();
    }

    /** Closes the request's open problems of these kinds as the system did. Returns how many it closed. */
    public int closeBySystem(UUID requestId, Collection<ProblemKind> kinds, Instant now) {
        return jdbc.sql("""
                        UPDATE problem SET status = 'RESOLVED', resolved_at = :now, decision = 'SYSTEM'
                         WHERE request_id = :requestId AND status = 'OPEN' AND kind IN (:kinds)""")
                .param("now", toSql(now))
                .param("requestId", requestId)
                .param("kinds", kinds.stream().map(Enum::name).toList())
                .update();
    }
}
