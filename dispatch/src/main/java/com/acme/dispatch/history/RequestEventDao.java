package com.acme.dispatch.history;

import static com.acme.dispatch.common.SqlTime.toSql;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Writes request events. Events are only added: there is no way here to change or remove one, and every write runs
 * in the transaction of the change it records.
 */
@Repository
public class RequestEventDao {

    private final JdbcClient jdbc;

    public RequestEventDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(RequestEvent event) {
        jdbc.sql("""
                        INSERT INTO request_event (id, request_id, version, occurred_at, actor_id, kind, changes,
                                                   inbox_id, problem_id)
                        VALUES (:id, :requestId, :version, :occurredAt, :actorId, :kind, CAST(:changes AS jsonb),
                                :inboxId, :problemId)""")
                .param("id", event.id())
                .param("requestId", event.requestId())
                .param("version", event.version())
                .param("occurredAt", toSql(event.occurredAt()))
                .param("actorId", event.actorId())
                .param("kind", event.kind().name())
                .param("changes", Change.toJson(event.changes()))
                .param("inboxId", event.inboxId())
                .param("problemId", event.problemId())
                .update();
    }
}
