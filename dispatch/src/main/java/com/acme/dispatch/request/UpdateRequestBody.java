package com.acme.dispatch.request;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.acme.dispatch.common.web.ApiException;
import com.acme.dispatch.common.web.ErrorCode;

import tools.jackson.databind.JsonNode;

/**
 * Body of {@code PATCH /api/requests/{id}}: {@code {version, assigneeId?, comment?}}.
 *
 * <p>Parsed from the JSON tree on purpose: data binding cannot tell an omitted field from an explicit
 * {@code null} (Jackson maps both to an empty {@code Optional}), and here they mean different things —
 * omitted = keep, {@code null} = clear. Unknown fields are rejected so a typo never passes silently.
 */
public record UpdateRequestBody(long version, FieldChange<UUID> assigneeId, FieldChange<String> comment) {

    static final int MAX_COMMENT_LENGTH = 2000;
    private static final Set<String> KNOWN_FIELDS = Set.of("version", "assigneeId", "comment");

    public static UpdateRequestBody parse(JsonNode body) {
        List<Map<String, Object>> errors = new ArrayList<>();
        if (body == null || !body.isObject()) {
            throw invalid(List.of(error("body", "must be a JSON object")));
        }
        for (String name : body.propertyNames()) {
            if (!KNOWN_FIELDS.contains(name)) {
                errors.add(error(name, "unknown field"));
            }
        }

        long version = 0;
        JsonNode versionNode = body.get("version");
        if (versionNode == null || versionNode.isNull()) {
            errors.add(error("version", "must not be null"));
        } else if (!versionNode.isIntegralNumber() || !versionNode.canConvertToLong() || versionNode.longValue() < 0) {
            errors.add(error("version", "must be a non-negative integer"));
        } else {
            version = versionNode.longValue();
        }

        FieldChange<UUID> assignee = FieldChange.absent();
        if (body.has("assigneeId")) {
            JsonNode node = body.get("assigneeId");
            if (node.isNull()) {
                assignee = FieldChange.of(null);
            } else if (node.isString() && isUuid(node.asString())) {
                assignee = FieldChange.of(UUID.fromString(node.asString()));
            } else {
                errors.add(error("assigneeId", "must be a UUID or null"));
            }
        }

        FieldChange<String> comment = FieldChange.absent();
        if (body.has("comment")) {
            JsonNode node = body.get("comment");
            if (node.isNull()) {
                comment = FieldChange.of(null);
            } else if (!node.isString()) {
                errors.add(error("comment", "must be a string or null"));
            } else {
                // Measure what is stored: the stripped text, in characters (code points) as varchar counts them.
                String text = node.asString().strip();
                if (text.codePointCount(0, text.length()) > MAX_COMMENT_LENGTH) {
                    errors.add(error("comment", "size must be at most " + MAX_COMMENT_LENGTH + " characters"));
                } else {
                    comment = FieldChange.of(text.isEmpty() ? null : text);
                }
            }
        }

        if (errors.isEmpty() && !assignee.present() && !comment.present()) {
            errors.add(error("body", "send at least one of: assigneeId, comment"));
        }
        if (!errors.isEmpty()) {
            throw invalid(errors);
        }
        return new UpdateRequestBody(version, assignee, comment);
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return value.length() == 36;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static Map<String, Object> error(String field, String message) {
        return Map.of("field", field, "message", message);
    }

    private static ApiException invalid(List<Map<String, Object>> errors) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "Some fields are invalid", Map.of("errors", errors));
    }
}
