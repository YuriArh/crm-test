package com.acme.dispatch.problem;

import static com.acme.dispatch.common.SqlTime.instant;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.acme.dispatch.auth.CurrentUser;
import com.acme.dispatch.exchange.inbox.ResultMessage;
import com.acme.dispatch.exchange.inbox.SafeText;
import com.acme.dispatch.request.BusinessResult;
import com.acme.dispatch.request.BusinessStatus;
import com.acme.dispatch.request.PersonRef;
import com.acme.dispatch.request.RequestNumber;

import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-side SQL for problems. An operator's scope: problems of the requests of their service and problems that name
 * no request. The scope is always the first condition.
 */
@Repository
public class ProblemQueryDao {

    /** Longest beginning of a record's raw text shown with its problem. */
    static final int EXCERPT_LENGTH = 2000;
    private static final Set<String> FINAL = Set.of("SUCCEEDED", "REJECTED");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String SELECT = """
            SELECT p.id, p.kind, p.status, p.details, p.opened_at, p.resolved_at, p.decision, p.comment,
                   p.request_id, r.number, r.business_status, r.tracking_number, r.price, r.rejection_reason,
                   r.result_at, coalesce(o.operation_id, i.operation_id) AS operation_id,
                   p.inbox_id, i.message_id, i.feed_seq, i.payload, i.result_status,
                   p.resolved_by, u.display_name AS resolved_by_name
              FROM problem p
              LEFT JOIN delivery_request r ON r.id = p.request_id
              LEFT JOIN dispatch_operation o ON o.request_id = p.request_id
              LEFT JOIN inbox i ON i.id = p.inbox_id
              LEFT JOIN app_user u ON u.id = p.resolved_by
            """;

    private final JdbcClient jdbc;

    public ProblemQueryDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ExchangeProblem> findPage(CurrentUser operator, ProblemListQuery query) {
        Map<String, Object> params = new HashMap<>();
        String where = where(operator, query, params);
        params.put("limit", query.size());
        params.put("offset", query.offset());
        return jdbc.sql(SELECT + where + " ORDER BY p.opened_at DESC, p.id DESC LIMIT :limit OFFSET :offset")
                .params(params)
                .query((rs, row) -> map(rs))
                .list();
    }

    public long count(CurrentUser operator, ProblemListQuery query) {
        Map<String, Object> params = new HashMap<>();
        String where = where(operator, query, params);
        return jdbc.sql("SELECT count(*) FROM problem p LEFT JOIN delivery_request r ON r.id = p.request_id " + where)
                .params(params)
                .query(Long.class)
                .single();
    }

    /** Every problem of a request, newest first; the caller has checked the request's scope. */
    public List<ExchangeProblem> forRequest(UUID requestId) {
        return jdbc.sql(SELECT + " WHERE p.request_id = :requestId ORDER BY p.opened_at DESC, p.id DESC")
                .param("requestId", requestId)
                .query((rs, row) -> map(rs))
                .list();
    }

    /** One problem in the operator's scope. */
    public Optional<ExchangeProblem> find(UUID id, CurrentUser operator) {
        return jdbc.sql(SELECT + " WHERE (p.request_id IS NULL OR r.service_id = :serviceId) AND p.id = :id")
                .param("serviceId", operator.serviceId())
                .param("id", id)
                .query((rs, row) -> map(rs))
                .optional();
    }

    private static String where(CurrentUser operator, ProblemListQuery query, Map<String, Object> params) {
        List<String> conditions = new ArrayList<>();
        conditions.add("(p.request_id IS NULL OR r.service_id = :serviceId)");
        params.put("serviceId", operator.serviceId());
        if (query.status() != null) {
            conditions.add("p.status = :status");
            params.put("status", query.status().name());
        }
        if (!query.kinds().isEmpty()) {
            conditions.add("p.kind IN (:kinds)");
            params.put("kinds", query.kinds().stream().map(Enum::name).toList());
        }
        return " WHERE " + String.join(" AND ", conditions);
    }

    private static ExchangeProblem map(ResultSet rs) throws SQLException {
        ProblemKind kind = ProblemKind.valueOf(rs.getString("kind"));
        ProblemStatus status = ProblemStatus.valueOf(rs.getString("status"));
        UUID requestId = rs.getObject("request_id", UUID.class);
        UUID resolvedBy = rs.getObject("resolved_by", UUID.class);
        String decision = rs.getString("decision");
        String recordStatus = rs.getString("result_status");
        return new ExchangeProblem(
                rs.getObject("id", UUID.class),
                kind,
                status,
                rs.getString("details"),
                instant(rs, "opened_at"),
                instant(rs, "resolved_at"),
                requestId == null ? null
                        : new ExchangeProblem.RequestRef(requestId, RequestNumber.format(rs.getLong("number"))),
                rs.getObject("operation_id", UUID.class),
                rs.getObject("inbox_id", UUID.class) == null ? null : message(rs),
                kind == ProblemKind.RESULT_CONFLICT ? currentResult(rs) : null,
                resolvedBy == null ? null : new PersonRef(resolvedBy, rs.getString("resolved_by_name")),
                decision == null ? null : ProblemDecision.valueOf(decision),
                rs.getString("comment"),
                ProblemRules.decisions(kind, status, recordStatus != null && FINAL.contains(recordStatus)));
    }

    /** The record as far as it can be read: a field that does not parse is {@code null}. */
    private static ExchangeProblem.Message message(ResultSet rs) throws SQLException {
        String payload = rs.getString("payload");
        long feedSeq = rs.getLong("feed_seq");
        Long position = rs.wasNull() ? null : feedSeq;
        ResultMessage parsed = parse(payload);
        return new ExchangeProblem.Message(
                rs.getString("message_id"),
                position,
                parsed == null ? null : parsed.status(),
                parsed == null ? null : parsed.sequence(),
                parsed == null ? null : parsed.trackingNumber(),
                parsed == null ? null : parsed.price(),
                parsed == null ? null : parsed.reason(),
                parsed == null ? null : parsed.occurredAt(),
                SafeText.of(payload, EXCERPT_LENGTH));
    }

    private static ResultMessage parse(String payload) {
        try {
            return ResultMessage.parse(JSON.readTree(payload));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static ExchangeProblem.CurrentResult currentResult(ResultSet rs) throws SQLException {
        String status = rs.getString("business_status");
        if (status == null) {
            return null;
        }
        Instant resultAt = instant(rs, "result_at");
        String trackingNumber = rs.getString("tracking_number");
        BigDecimal price = rs.getBigDecimal("price");
        String rejectionReason = rs.getString("rejection_reason");
        BusinessResult result = resultAt == null && trackingNumber == null && price == null && rejectionReason == null
                ? null
                : new BusinessResult(trackingNumber, price, rejectionReason, resultAt);
        return new ExchangeProblem.CurrentResult(BusinessStatus.valueOf(status), result);
    }
}
