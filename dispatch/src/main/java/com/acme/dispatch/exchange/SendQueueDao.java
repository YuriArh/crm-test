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
     * Operations left in {@code SENDING} past their lease: the worker died mid-send. The caller decides each by the
     * retry policy ({@link #rescheduleInterrupted}, {@link #failInterrupted}). Locked until the caller's transaction
     * ends; rows another instance is recovering are skipped.
     */
    List<Expired> findExpired(Instant now) {
        return jdbc.sql("""
                        SELECT request_id, operation_id, attempts, retry_from FROM dispatch_operation
                         WHERE delivery_status = 'SENDING' AND locked_until < :now
                         ORDER BY locked_until
                           FOR UPDATE SKIP LOCKED""")
                .param("now", toSql(now))
                .query((rs, row) -> new Expired(
                        rs.getObject("request_id", UUID.class),
                        rs.getObject("operation_id", UUID.class),
                        rs.getInt("attempts"),
                        rs.getInt("retry_from")))
                .list();
    }

    /** The interrupted attempt may be followed by another one: {@code UNKNOWN} until {@code next}. */
    boolean rescheduleInterrupted(Expired expired, Instant next, String error, Instant now) {
        return jdbc.sql("""
                        UPDATE dispatch_operation
                           SET delivery_status = 'UNKNOWN', locked_until = NULL, next_attempt_at = :next,
                               last_error = :error, updated_at = :now
                         WHERE request_id = :requestId AND delivery_status = 'SENDING' AND attempts = :attempt
                           AND locked_until < :now""")
                .param("next", toSql(next))
                .param("error", error)
                .param("now", toSql(now))
                .param("requestId", expired.requestId())
                .param("attempt", expired.attempt())
                .update() == 1;
    }

    /** The interrupted attempt was the last one allowed: an operator decides. */
    boolean failInterrupted(Expired expired, String error, Instant now) {
        return jdbc.sql("""
                        UPDATE dispatch_operation
                           SET delivery_status = 'FAILED', locked_until = NULL, last_error = :error, updated_at = :now
                         WHERE request_id = :requestId AND delivery_status = 'SENDING' AND attempts = :attempt
                           AND locked_until < :now""")
                .param("error", error)
                .param("now", toSql(now))
                .param("requestId", expired.requestId())
                .param("attempt", expired.attempt())
                .update() == 1;
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
                        RETURNING o.request_id, o.operation_id, o.attempts, o.retry_from,
                                  r.cargo_description, r.weight_kg, r.delivery_address, r.deadline""")
                .param("now", toSql(now))
                .param("leaseUntil", toSql(leaseUntil))
                .query((rs, row) -> new Claimed(
                        rs.getObject("request_id", UUID.class),
                        rs.getObject("operation_id", UUID.class),
                        rs.getInt("attempts"),
                        rs.getInt("retry_from"),
                        new SubmitRequest(
                                rs.getObject("operation_id", UUID.class),
                                rs.getString("cargo_description"),
                                DeliveryContent.canonicalWeight(rs.getBigDecimal("weight_kg")),
                                rs.getString("delivery_address"),
                                rs.getObject("deadline", LocalDate.class)),
                        now))
                .optional();
    }

    /**
     * An operator retries the delivery: a new budget of retries starting from the attempts made so far, due now.
     * {@code FAILED} becomes {@code PENDING}; {@code UNKNOWN} stays {@code UNKNOWN} (the carrier may have it); an
     * {@code ACKNOWLEDGED} operation the carrier lost ({@code EXTERNAL_MISSING} open) without a result becomes
     * {@code PENDING} and forgets its acknowledgement. The reconciliation schedule starts over. Conditional on that
     * state, the same rule as {@code ProblemRules.canRetry}, so it does nothing to an operation that moved on.
     * Returns the delivery status before and after, or empty if it did not retry.
     */
    public Optional<Retried> retryByOperator(UUID requestId, Instant now) {
        // Lock the operation first: the result handler changes the request's result under this lock, so the
        // next statement (a fresh snapshot) sees whether the request has a result now. The status read under the
        // lock is the one the retry changes.
        Optional<String> before = jdbc.sql("""
                        SELECT delivery_status FROM dispatch_operation WHERE request_id = :requestId FOR UPDATE""")
                .param("requestId", requestId)
                .query(String.class)
                .optional();
        if (before.isEmpty()) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        UPDATE dispatch_operation o
                           SET delivery_status = CASE o.delivery_status WHEN 'UNKNOWN' THEN 'UNKNOWN'
                                                     ELSE 'PENDING' END,
                               retry_from = o.attempts, next_attempt_at = :now, last_error = NULL,
                               acknowledged_at = NULL, acknowledged_via = NULL,
                               reconciled_at = NULL, next_reconcile_at = NULL, updated_at = :now
                          FROM delivery_request r
                         WHERE r.id = o.request_id AND o.request_id = :requestId
                           AND (o.delivery_status IN ('FAILED', 'UNKNOWN')
                                OR (o.delivery_status = 'ACKNOWLEDGED'
                                    AND r.business_status NOT IN ('SUCCEEDED', 'REJECTED')
                                    AND EXISTS (SELECT 1 FROM problem p
                                                 WHERE p.request_id = o.request_id AND p.status = 'OPEN'
                                                   AND p.kind = 'EXTERNAL_MISSING')))
                        RETURNING o.delivery_status""")
                .param("now", toSql(now))
                .param("requestId", requestId)
                .query((rs, row) -> new Retried(before.get(), rs.getString("delivery_status")))
                .optional();
    }

    /** The delivery status an operator's retry found and left. */
    public record Retried(String from, String to) {
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
     * @param attempt   number of this attempt (1-based, never reset); also the fencing token for the completion update
     * @param retryFrom the attempt number the current retry budget started from (an operator's retry)
     * @param claimedAt when it was claimed: the start of the attempt, written to {@code exchange_attempt.started_at}
     *                  and the base of its duration
     */
    record Claimed(UUID requestId, UUID operationId, int attempt, int retryFrom, SubmitRequest request,
                   Instant claimedAt) {

        /** Number of this attempt within the current retry budget: what the retry policy counts. */
        int attemptInBudget() {
            return attempt - retryFrom;
        }
    }

    /**
     * An operation whose send was interrupted.
     *
     * @param attempt   number of the interrupted attempt, already counted when it was claimed; the fencing token
     * @param retryFrom the attempt number the current retry budget started from
     */
    record Expired(UUID requestId, UUID operationId, int attempt, int retryFrom) {

        /** Number of the interrupted attempt within the current retry budget. */
        int attemptInBudget() {
            return attempt - retryFrom;
        }
    }
}
