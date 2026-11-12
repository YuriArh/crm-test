package com.acme.dispatch.request;

import static com.acme.dispatch.common.SqlTime.instant;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.acme.dispatch.auth.CurrentUser;
import com.acme.dispatch.exchange.DeliveryStatus;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-side SQL for the request registry and card. The access scope is always the first WHERE
 * condition and comes only from the authenticated user; filters and ordering come from a
 * validated {@link RequestListQuery} (ordering from a whitelist of columns).
 */
@Repository
public class RequestQueryDao {

    private static final String LIST_COLUMNS = """
            SELECT r.id, r.number, r.cargo_description, r.deadline, r.business_status, r.created_at,
                   o.delivery_status,
                   r.owner_id, ou.display_name AS owner_name,
                   r.assignee_id, au.display_name AS assignee_name
            """;

    private static final String FROM = """
            FROM delivery_request r
            JOIN dispatch_operation o ON o.request_id = r.id
            JOIN app_user ou ON ou.id = r.owner_id
            LEFT JOIN app_user au ON au.id = r.assignee_id
            """;

    private static final String OVERDUE_CONDITION =
            "(r.business_status IN ('AWAITING', 'IN_PROGRESS') AND r.deadline < :today)";

    private final JdbcClient jdbc;

    public RequestQueryDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One page of the registry within the viewer's scope. */
    public List<RequestListItem> findPage(CurrentUser viewer, RequestListQuery query, LocalDate today) {
        Map<String, Object> params = new HashMap<>();
        String where = where(viewer, query, today, params);
        String direction = query.ascending() ? "ASC" : "DESC";
        String sql = LIST_COLUMNS + FROM + where
                + " ORDER BY " + query.sortField().column() + " " + direction + ", r.id " + direction
                + " LIMIT :limit OFFSET :offset";
        params.put("limit", query.size());
        params.put("offset", query.offset());
        return jdbc.sql(sql)
                .params(params)
                .query((rs, rowNum) -> mapListItem(rs, today))
                .list();
    }

    /** Number of registry rows matching the same scope and filters as {@link #findPage}. */
    public long count(CurrentUser viewer, RequestListQuery query, LocalDate today) {
        Map<String, Object> params = new HashMap<>();
        String sql = "SELECT count(*) " + FROM + where(viewer, query, today, params);
        return jdbc.sql(sql)
                .params(params)
                .query(Long.class)
                .single();
    }

    /**
     * Loads a card regardless of scope; the caller checks visibility with {@link RequestPermissions}
     * using {@link CardRow#ownerId()} and {@link CardRow#serviceId()}. The card always contains
     * the exchange block.
     */
    public Optional<CardRow> findCard(UUID id, LocalDate today) {
        return jdbc.sql("""
                        SELECT r.*, o.operation_id, o.delivery_status, o.attempts, o.next_attempt_at,
                               o.last_error, o.acknowledged_at, o.acknowledged_via,
                               ou.display_name AS owner_name, au.display_name AS assignee_name
                        """ + FROM + "WHERE r.id = :id")
                .param("id", id)
                .query((rs, rowNum) -> mapCard(rs, today))
                .optional();
    }

    /** A card with the ids needed for the visibility check. */
    public record CardRow(RequestCard card, UUID ownerId, UUID serviceId) {
    }

    private static String where(CurrentUser viewer, RequestListQuery query, LocalDate today,
                                Map<String, Object> params) {
        StringBuilder where = new StringBuilder();
        if (viewer.isOperator()) {
            where.append("WHERE r.service_id = :serviceId");
            params.put("serviceId", viewer.serviceId());
        } else {
            where.append("WHERE r.owner_id = :userId");
            params.put("userId", viewer.id());
        }
        if (query.businessStatus() != null) {
            where.append(" AND r.business_status = :businessStatus");
            params.put("businessStatus", query.businessStatus().name());
        }
        if (query.deliveryStatus() != null) {
            where.append(" AND o.delivery_status = :deliveryStatus");
            params.put("deliveryStatus", query.deliveryStatus().name());
        }
        if (query.overdue() != null) {
            where.append(query.overdue() ? " AND " : " AND NOT ").append(OVERDUE_CONDITION);
            params.put("today", today);
        }
        return where.toString();
    }

    private static RequestListItem mapListItem(ResultSet rs, LocalDate today) throws SQLException {
        BusinessStatus status = BusinessStatus.valueOf(rs.getString("business_status"));
        LocalDate deadline = rs.getObject("deadline", LocalDate.class);
        return new RequestListItem(
                rs.getObject("id", UUID.class),
                RequestNumber.format(rs.getLong("number")),
                rs.getString("cargo_description"),
                deadline,
                status,
                DeliveryStatus.valueOf(rs.getString("delivery_status")),
                OverduePolicy.isOverdue(status, deadline, today),
                owner(rs),
                assignee(rs),
                instant(rs, "created_at"));
    }

    private static CardRow mapCard(ResultSet rs, LocalDate today) throws SQLException {
        BusinessStatus status = BusinessStatus.valueOf(rs.getString("business_status"));
        LocalDate deadline = rs.getObject("deadline", LocalDate.class);
        Instant resultAt = instant(rs, "result_at");
        String trackingNumber = rs.getString("tracking_number");
        BigDecimal price = rs.getBigDecimal("price");
        String rejectionReason = rs.getString("rejection_reason");
        BusinessResult result = resultAt == null && trackingNumber == null && price == null && rejectionReason == null
                ? null
                : new BusinessResult(trackingNumber, price, rejectionReason, resultAt);
        // next_attempt_at is only meaningful while the operation is waiting in the send queue.
        boolean queued = Set.of("PENDING", "RETRY_WAIT", "UNKNOWN").contains(rs.getString("delivery_status"));
        ExchangeInfo exchange = new ExchangeInfo(
                rs.getInt("attempts"),
                queued ? instant(rs, "next_attempt_at") : null,
                rs.getString("last_error"),
                instant(rs, "acknowledged_at"),
                rs.getString("acknowledged_via"));
        RequestCard card = new RequestCard(
                rs.getObject("id", UUID.class),
                RequestNumber.format(rs.getLong("number")),
                rs.getString("cargo_description"),
                rs.getBigDecimal("weight_kg"),
                rs.getString("delivery_address"),
                deadline,
                rs.getString("comment"),
                owner(rs),
                assignee(rs),
                status,
                result,
                DeliveryStatus.valueOf(rs.getString("delivery_status")),
                rs.getObject("operation_id", UUID.class),
                OverduePolicy.isOverdue(status, deadline, today),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                rs.getLong("version"),
                exchange);
        return new CardRow(card, rs.getObject("owner_id", UUID.class), rs.getObject("service_id", UUID.class));
    }

    private static PersonRef owner(ResultSet rs) throws SQLException {
        return new PersonRef(rs.getObject("owner_id", UUID.class), rs.getString("owner_name"));
    }

    private static PersonRef assignee(ResultSet rs) throws SQLException {
        UUID assigneeId = rs.getObject("assignee_id", UUID.class);
        return assigneeId == null ? null : new PersonRef(assigneeId, rs.getString("assignee_name"));
    }
}
