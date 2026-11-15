package com.acme.dispatch.exchange;

import static com.acme.dispatch.common.SqlTime.toSql;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.acme.dispatch.carrier.SubmitResult;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** History of send attempts ({@code exchange_attempt}): one row per attempt, closed with its outcome. */
@Repository
public class ExchangeAttemptDao {

    private final JdbcClient jdbc;

    public ExchangeAttemptDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void start(UUID id, UUID operationId, int attemptNo, Instant startedAt) {
        jdbc.sql("""
                        INSERT INTO exchange_attempt (id, operation_id, attempt_no, started_at)
                        VALUES (:id, :operationId, :attemptNo, :startedAt)""")
                .param("id", id)
                .param("operationId", operationId)
                .param("attemptNo", attemptNo)
                .param("startedAt", toSql(startedAt))
                .update();
    }

    void finish(UUID operationId, int attemptNo, SubmitResult result, Instant finishedAt, Duration duration) {
        jdbc.sql("""
                        UPDATE exchange_attempt
                           SET finished_at = :finishedAt, outcome = :outcome, http_status = :httpStatus, error = :error,
                               duration_ms = :durationMs
                         WHERE operation_id = :operationId AND attempt_no = :attemptNo AND finished_at IS NULL""")
                .param("finishedAt", toSql(finishedAt))
                .param("outcome", result.outcome().name())
                .param("httpStatus", result.httpStatus())
                .param("error", result.error())
                .param("durationMs", (int) Math.max(duration.toMillis(), 0))
                .param("operationId", operationId)
                .param("attemptNo", attemptNo)
                .update();
    }

    /**
     * Attempts still open after the lease were cut short by a worker crash: they end with an unknown outcome.
     * The operation's state is not consulted — a result or (later) an operator may have moved it out of
     * {@code SENDING} before the lease ran out, and nobody else would close the attempt.
     *
     * @param startedBefore attempts started strictly before this instant are closed (now minus the lease)
     * @return how many attempts were closed
     */
    int finishExpired(Instant now, Instant startedBefore) {
        return jdbc.sql("""
                        UPDATE exchange_attempt
                           SET finished_at = :now, outcome = 'UNKNOWN', error = 'Send was interrupted (lease expired)'
                         WHERE finished_at IS NULL AND started_at < :startedBefore""")
                .param("now", toSql(now))
                .param("startedBefore", toSql(startedBefore))
                .update();
    }
}
