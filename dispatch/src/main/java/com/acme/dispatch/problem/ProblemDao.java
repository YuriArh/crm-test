package com.acme.dispatch.problem;

import static com.acme.dispatch.common.SqlTime.toSql;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

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
