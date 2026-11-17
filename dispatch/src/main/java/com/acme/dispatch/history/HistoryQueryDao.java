package com.acme.dispatch.history;

import static com.acme.dispatch.common.SqlTime.toSql;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.acme.dispatch.request.PersonRef;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * A request's history in one query: its events and its exchange, each from the table it is kept in
 * (docs/specs/request-history.md §2.1). Each source gives its kind-specific fields as one {@code jsonb}.
 */
@Repository
class HistoryQueryDao {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The order of kinds at one instant, as SQL. */
    private static final String RANK = Arrays.stream(HistoryKind.values())
            .map(kind -> "WHEN '" + kind.name() + "' THEN " + kind.ordinal())
            .collect(Collectors.joining(" ", "CASE e.kind ", " END"));

    private static final String ENTRIES = """
            WITH req AS (
                SELECT r.id, r.owner_id, r.created_at, o.operation_id, o.acknowledged_at, o.acknowledged_via
                  FROM delivery_request r JOIN dispatch_operation o ON o.request_id = r.id
                 WHERE r.id = :id),
            entries AS (
                SELECT 'CREATED' AS kind, req.created_at AS at, req.id, req.owner_id AS actor_id, NULL::jsonb AS data
                  FROM req
                UNION ALL
                SELECT ev.kind, ev.occurred_at, ev.id, ev.actor_id,
                       jsonb_build_object('version', ev.version, 'changes', ev.changes, 'recordId', ev.inbox_id,
                                          'problemId', ev.problem_id)
                  FROM request_event ev WHERE ev.request_id = :id
                UNION ALL
                SELECT 'DELIVERY_ACKNOWLEDGED', req.acknowledged_at, req.operation_id, NULL,
                       jsonb_build_object('via', req.acknowledged_via)
                  FROM req WHERE req.acknowledged_at IS NOT NULL
                UNION ALL
                SELECT 'SEND_ATTEMPT', a.started_at, a.id, NULL,
                       jsonb_build_object('attemptNo', a.attempt_no, 'outcome', a.outcome, 'httpStatus', a.http_status,
                                          'error', a.error, 'durationMs', a.duration_ms, 'finishedAt', a.finished_at)
                  FROM exchange_attempt a JOIN req ON a.operation_id = req.operation_id
                UNION ALL
                SELECT 'RESULT_RECEIVED', coalesce(i.processed_at, i.received_at), i.id, NULL,
                       jsonb_build_object('source', i.source, 'resultStatus', i.result_status,
                                          'sequence', i.sequence, 'processingStatus', i.processing_status,
                                          'reason', i.reason, 'duplicates', i.duplicate_count,
                                          'lastDuplicateAt', i.last_duplicate_at, 'receivedAt', i.received_at)
                  FROM inbox i JOIN req ON i.operation_id = req.operation_id
                UNION ALL
                SELECT 'RECONCILIATION', c.checked_at, c.id, c.performed_by,
                       jsonb_build_object('trigger', c.run_type, 'outcome', c.outcome, 'details', c.details)
                  FROM reconciliation c WHERE c.request_id = :id
                UNION ALL
                SELECT 'PROBLEM_OPENED', p.opened_at, p.id, NULL, jsonb_build_object('problemKind', p.kind)
                  FROM problem p WHERE p.request_id = :id
                UNION ALL
                SELECT 'PROBLEM_RESOLVED', p.resolved_at, p.id, p.resolved_by,
                       jsonb_build_object('problemKind', p.kind, 'decision', p.decision, 'comment', p.comment)
                  FROM problem p WHERE p.request_id = :id AND p.resolved_at IS NOT NULL),
            ranked AS (
                SELECT e.*, %s AS rank FROM entries e WHERE e.kind IN (:kinds))
            SELECT k.kind, k.at, k.id, k.actor_id, u.display_name AS actor_name, k.data::text AS data
              FROM ranked k LEFT JOIN app_user u ON u.id = k.actor_id
             %s
             ORDER BY k.at DESC, k.rank DESC, k.id DESC
             LIMIT :limit""";

    private final JdbcClient jdbc;

    HistoryQueryDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Owner and service of a request, to check who may see it. */
    Optional<Scope> scopeOf(UUID requestId) {
        return jdbc.sql("SELECT owner_id, service_id FROM delivery_request WHERE id = :id")
                .param("id", requestId)
                .query((rs, row) -> new Scope(rs.getObject("owner_id", UUID.class),
                        rs.getObject("service_id", UUID.class)))
                .optional();
    }

    /** At most {@code limit} entries of the given kinds older than {@code before} (all, if it is {@code null}). */
    List<Row> page(UUID requestId, Set<HistoryKind> kinds, HistoryCursor before, int limit) {
        String after = before == null ? "" : "WHERE (k.at, k.rank, k.id) < (:beforeAt, :beforeRank, :beforeId)";
        JdbcClient.StatementSpec sql = jdbc.sql(ENTRIES.formatted(RANK, after))
                .param("id", requestId)
                .param("kinds", kinds.stream().map(Enum::name).toList())
                .param("limit", limit);
        if (before != null) {
            sql = sql.param("beforeAt", toSql(before.at()))
                    .param("beforeRank", before.kind().ordinal())
                    .param("beforeId", before.id());
        }
        return sql.query((rs, row) -> {
                    UUID actorId = rs.getObject("actor_id", UUID.class);
                    String data = rs.getString("data");
                    return new Row(HistoryKind.valueOf(rs.getString("kind")),
                            rs.getObject("at", OffsetDateTime.class).toInstant(), rs.getObject("id", UUID.class),
                            actorId == null ? null : new PersonRef(actorId, rs.getString("actor_name")),
                            data == null ? JSON.createObjectNode() : JSON.readTree(data));
                })
                .list();
    }

    /** The people with these ids, by id. */
    Map<UUID, PersonRef> people(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("SELECT id, display_name FROM app_user WHERE id IN (:ids)")
                .param("ids", ids)
                .query((rs, row) -> new PersonRef(rs.getObject("id", UUID.class), rs.getString("display_name")))
                .list()
                .stream()
                .collect(Collectors.toMap(PersonRef::id, Function.identity()));
    }

    record Scope(UUID ownerId, UUID serviceId) {
    }

    /** One entry as stored: its kind-specific fields in {@code data}. */
    record Row(HistoryKind kind, java.time.Instant at, UUID id, PersonRef actor, JsonNode data) {
    }
}
