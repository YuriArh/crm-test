package com.acme.dispatch.support;

import java.util.List;
import java.util.UUID;

import com.acme.dispatch.history.Change;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Reads a request's events for assertions, oldest first. */
public final class TestEvents {

    private TestEvents() {
    }

    /**
     * One event as {@code KIND v<version> by <actor|system> [field: from → to, …]}; the retry's version is {@code -}.
     */
    public static List<String> of(JdbcClient jdbc, UUID requestId) {
        return jdbc.sql("""
                        SELECT kind, version, actor_id, changes::text AS changes FROM request_event
                         WHERE request_id = :id ORDER BY occurred_at, version NULLS LAST, id""")
                .param("id", requestId)
                .query((rs, n) -> rs.getString("kind") + " v" + (rs.getObject("version") == null ? "-"
                        : rs.getLong("version")) + " by " + (rs.getObject("actor_id") == null ? "system"
                        : rs.getObject("actor_id", UUID.class)) + " " + describe(Change.listOf(rs.getString("changes"))))
                .list();
    }

    /** The record and the problem of each event, as {@code <inbox|->/<problem|->}, oldest first. */
    public static List<String> links(JdbcClient jdbc, UUID requestId) {
        return jdbc.sql("""
                        SELECT coalesce(inbox_id::text, '-') || '/' || coalesce(problem_id::text, '-')
                          FROM request_event WHERE request_id = :id ORDER BY occurred_at, version NULLS LAST, id""")
                .param("id", requestId)
                .query(String.class)
                .list();
    }

    private static String describe(List<Change> changes) {
        return changes.stream().map(change -> change.field() + ": " + change.from() + " → " + change.to())
                .toList().toString();
    }
}
