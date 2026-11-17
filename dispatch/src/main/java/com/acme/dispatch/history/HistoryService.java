package com.acme.dispatch.history;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.acme.dispatch.auth.CurrentUser;
import com.acme.dispatch.common.web.ApiException;
import com.acme.dispatch.history.HistoryEntry.AssigneeChange;
import com.acme.dispatch.history.HistoryEntry.FieldChange;
import com.acme.dispatch.history.HistoryEntry.ValueChange;
import com.acme.dispatch.history.HistoryQueryDao.Row;
import com.acme.dispatch.request.PersonRef;
import com.acme.dispatch.request.RequestPermissions;

import tools.jackson.databind.JsonNode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads a request's history for whoever may see the request: an operator of its service sees everything, its
 * initiator the business part without links to carrier records and problems (docs/specs/request-history.md §2.4).
 */
@Service
public class HistoryService {

    /** Stored field names that differ from the API. */
    private static final String ASSIGNEE_ID = "assigneeId";

    private final HistoryQueryDao dao;

    public HistoryService(HistoryQueryDao dao) {
        this.dao = dao;
    }

    /**
     * @throws ApiException {@code NOT_FOUND} if the request does not exist or the viewer may not see it
     */
    @Transactional(readOnly = true)
    public HistoryPage page(UUID requestId, CurrentUser viewer, HistoryCursor before, int limit) {
        HistoryQueryDao.Scope scope = dao.scopeOf(requestId)
                .filter(found -> RequestPermissions.canView(viewer, found.ownerId(), found.serviceId()))
                .orElseThrow(() -> ApiException.notFound("Request " + requestId + " not found"));
        boolean operator = viewer.isOperator();
        List<Row> rows = dao.page(requestId, HistoryRules.visibleKinds(operator), before, limit + 1);
        boolean more = rows.size() > limit;
        List<Row> shown = more ? rows.subList(0, limit) : rows;
        Map<UUID, PersonRef> people = dao.people(assignees(shown));

        List<HistoryEntry> items = new ArrayList<>();
        for (Row row : shown) {
            items.add(entry(row, operator, people));
        }
        Row last = shown.isEmpty() ? null : shown.getLast();
        String next = more ? new HistoryCursor(last.at(), last.kind(), last.id()).encode() : null;
        return new HistoryPage(items, next);
    }

    private static HistoryEntry entry(Row row, boolean operator, Map<UUID, PersonRef> people) {
        JsonNode d = row.data();
        return switch (row.kind()) {
            case CREATED -> new HistoryEntry.Created(row.id(), row.kind(), row.at(), row.actor());
            case EDITED -> new HistoryEntry.Edited(row.id(), row.kind(), row.at(), row.actor(),
                    d.get("version").asLong(), changes(d, people));
            case RESULT_CHANGED -> new HistoryEntry.ResultChanged(row.id(), row.kind(), row.at(), row.actor(),
                    d.get("version").asLong(), changes(d, people),
                    operator ? uuid(d, "recordId") : null, operator ? uuid(d, "problemId") : null);
            case DELIVERY_ACKNOWLEDGED -> new HistoryEntry.DeliveryAcknowledged(row.id(), row.kind(), row.at(),
                    row.actor(), operator ? text(d, "via") : null);
            case DELIVERY_RETRIED -> new HistoryEntry.DeliveryRetried(row.id(), row.kind(), row.at(), row.actor(),
                    changes(d, people));
            case SEND_ATTEMPT -> new HistoryEntry.SendAttempt(row.id(), row.kind(), row.at(), row.actor(),
                    d.get("attemptNo").asInt(), text(d, "outcome"), integer(d, "httpStatus"), text(d, "error"),
                    integer(d, "durationMs"), instant(d, "finishedAt"));
            case RESULT_RECEIVED -> new HistoryEntry.ResultReceived(row.id(), row.kind(), row.at(), row.actor(),
                    row.id(), text(d, "source"), text(d, "resultStatus"), integer(d, "sequence"),
                    text(d, "processingStatus"), text(d, "reason"), d.get("duplicates").asInt(),
                    instant(d, "lastDuplicateAt"), instant(d, "receivedAt"));
            case RECONCILIATION -> new HistoryEntry.Reconciliation(row.id(), row.kind(), row.at(), row.actor(),
                    text(d, "trigger"), text(d, "outcome"), text(d, "details"));
            case PROBLEM_OPENED -> new HistoryEntry.ProblemOpened(row.id(), row.kind(), row.at(), row.actor(),
                    row.id(), text(d, "problemKind"));
            case PROBLEM_RESOLVED -> new HistoryEntry.ProblemResolved(row.id(), row.kind(), row.at(), row.actor(),
                    row.id(), text(d, "problemKind"), text(d, "decision"), text(d, "comment"));
        };
    }

    /** The stored changes in the API's terms: the assignee as a person. */
    private static List<FieldChange> changes(JsonNode data, Map<UUID, PersonRef> people) {
        List<FieldChange> changes = new ArrayList<>();
        for (Change change : Change.listOf(data.get("changes").toString())) {
            if (ASSIGNEE_ID.equals(change.field())) {
                changes.add(new AssigneeChange("assignee", person(change.from(), people),
                        person(change.to(), people)));
            } else {
                changes.add(new ValueChange(change.field(), change.from(), change.to()));
            }
        }
        return changes;
    }

    private static Set<UUID> assignees(List<Row> rows) {
        Set<UUID> ids = new HashSet<>();
        for (Row row : rows) {
            JsonNode changes = row.data().get("changes");
            if (changes == null || !changes.isArray()) {
                continue;
            }
            for (Change change : Change.listOf(changes.toString())) {
                if (ASSIGNEE_ID.equals(change.field())) {
                    if (change.from() != null) {
                        ids.add(UUID.fromString(change.from()));
                    }
                    if (change.to() != null) {
                        ids.add(UUID.fromString(change.to()));
                    }
                }
            }
        }
        return ids;
    }

    private static PersonRef person(String id, Map<UUID, PersonRef> people) {
        return id == null ? null : people.get(UUID.fromString(id));
    }

    private static String text(JsonNode data, String field) {
        JsonNode value = data.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static Integer integer(JsonNode data, String field) {
        JsonNode value = data.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }

    private static UUID uuid(JsonNode data, String field) {
        String value = text(data, field);
        return value == null ? null : UUID.fromString(value);
    }

    private static Instant instant(JsonNode data, String field) {
        String value = text(data, field);
        return value == null ? null : OffsetDateTime.parse(value).toInstant();
    }
}
