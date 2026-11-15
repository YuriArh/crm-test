package com.acme.dispatch.exchange;

import static com.acme.dispatch.common.SqlTime.toSql;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.acme.dispatch.carrier.SubmitRequest;
import com.acme.dispatch.request.DeliveryContent;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The send queue over {@code dispatch_operation}. Every transition is a conditional {@code UPDATE}, so a worker
 * that lost its claim (lease expired, or a result already confirmed delivery) cannot overwrite newer state.
 */
@Repository
public class SendQueueDao {

    private final JdbcClient jdbc;

    public SendQueueDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Operations left in {@code SENDING} past their lease (the worker died mid-send) become {@code UNKNOWN}
     * and are due at once: the carrier may or may not have them, and a resend with the same operationId is safe.
     *
     * @return operationIds of the recovered operations
     */
    List<UUID> recoverExpired(Instant now) {
        return jdbc.sql("""
                        UPDATE dispatch_operation
                           SET delivery_status = 'UNKNOWN', locked_until = NULL, next_attempt_at = :now,
                               last_error = 'Send was interrupted (lease expired)', updated_at = :now
                         WHERE delivery_status = 'SENDING' AND locked_until < :now
                        RETURNING operation_id""")
                .param("now", toSql(now))
                .query(UUID.class)
                .list();
    }

    /**
     * Claims the most overdue operation for sending, right before it is sent: {@code SENDING} with a lease, one more
     * attempt counted. One at a time because the worker sends one at a time — a claimed batch would wait for its
     * turn under a lease that is already running ({@code docs/dispatch-design.md} §5).
     */
    Optional<Claimed> claimNext(Instant now, Instant leaseUntil) {
        return jdbc.sql("""
                        UPDATE dispatch_operation o
                           SET delivery_status = 'SENDING', locked_until = :leaseUntil, attempts = o.attempts + 1,
                               updated_at = :now
                          FROM delivery_request r
                         WHERE r.id = o.request_id
                           AND o.request_id = (
                               SELECT request_id FROM dispatch_operation
                                WHERE delivery_status IN ('PENDING', 'RETRY_WAIT', 'UNKNOWN')
                                  AND next_attempt_at <= :now
                                ORDER BY next_attempt_at, request_id
                                LIMIT 1
                                FOR UPDATE SKIP LOCKED)
                        RETURNING o.request_id, o.operation_id, o.attempts,
                                  r.cargo_description, r.weight_kg, r.delivery_address, r.deadline""")
                .param("now", toSql(now))
                .param("leaseUntil", toSql(leaseUntil))
                .query((rs, row) -> new Claimed(
                        rs.getObject("request_id", UUID.class),
                        rs.getObject("operation_id", UUID.class),
                        rs.getInt("attempts"),
                        new SubmitRequest(
                                rs.getObject("operation_id", UUID.class),
                                rs.getString("cargo_description"),
                                DeliveryContent.canonicalWeight(rs.getBigDecimal("weight_kg")),
                                rs.getString("delivery_address"),
                                rs.getObject("deadline", LocalDate.class)),
                        now))
                .optional();
    }

    /** The carrier confirmed receipt. */
    boolean acknowledge(Claimed claimed, Instant now) {
        return jdbc.sql("""
                        UPDATE dispatch_operation
                           SET delivery_status = 'ACKNOWLEDGED', locked_until = NULL, last_error = NULL,
                               acknowledged_at = :now, acknowledged_via = 'RESPONSE', updated_at = :now
                         WHERE request_id = :requestId AND delivery_status = 'SENDING' AND attempts = :attempt""")
                .param("now", toSql(now))
                .param("requestId", claimed.requestId())
                .param("attempt", claimed.attempt())
                .update() == 1;
    }

    /** The attempt failed but another one is allowed: {@code RETRY_WAIT} or {@code UNKNOWN} until {@code next}. */
    boolean scheduleRetry(Claimed claimed, DeliveryStatus status, Instant next, String error, Instant now) {
        return jdbc.sql("""
                        UPDATE dispatch_operation
                           SET delivery_status = :status, locked_until = NULL, next_attempt_at = :next,
                               last_error = :error, updated_at = :now
                         WHERE request_id = :requestId AND delivery_status = 'SENDING' AND attempts = :attempt""")
                .param("status", status.name())
                .param("next", toSql(next))
                .param("error", error)
                .param("now", toSql(now))
                .param("requestId", claimed.requestId())
                .param("attempt", claimed.attempt())
                .update() == 1;
    }

    /** Permanent error or attempts exhausted: an operator decides. */
    boolean fail(Claimed claimed, String error, Instant now) {
        return jdbc.sql("""
                        UPDATE dispatch_operation
                           SET delivery_status = 'FAILED', locked_until = NULL, last_error = :error, updated_at = :now
                         WHERE request_id = :requestId AND delivery_status = 'SENDING' AND attempts = :attempt""")
                .param("error", error)
                .param("now", toSql(now))
                .param("requestId", claimed.requestId())
                .param("attempt", claimed.attempt())
                .update() == 1;
    }

    /**
     * An operation claimed for sending.
     *
     * @param attempt   number of this attempt (1-based); also the fencing token for the completion update
     * @param claimedAt when it was claimed: the start of the attempt, written to {@code exchange_attempt.started_at}
     *                  and the base of its duration
     */
    record Claimed(UUID requestId, UUID operationId, int attempt, SubmitRequest request, Instant claimedAt) {
    }
}
