package com.acme.dispatch.history;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

import com.acme.dispatch.common.web.ApiException;
import com.acme.dispatch.common.web.ErrorCode;

/** Who sees which part of a request's history, and the page size (docs/specs/request-history.md §2.4–2.5). */
public final class HistoryRules {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    private HistoryRules() {
    }

    /** An operator of the request's service sees everything; its initiator sees the business part. */
    public static Set<HistoryKind> visibleKinds(boolean operator) {
        EnumSet<HistoryKind> kinds = EnumSet.noneOf(HistoryKind.class);
        Arrays.stream(HistoryKind.values()).filter(kind -> operator || kind.isBusiness()).forEach(kinds::add);
        return kinds;
    }

    /** @throws ApiException {@code VALIDATION_FAILED} outside {@code 1..200} */
    public static int limit(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_LIMIT;
        }
        try {
            int limit = Integer.parseInt(value.trim());
            if (limit >= 1 && limit <= MAX_LIMIT) {
                return limit;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        throw new ApiException(ErrorCode.VALIDATION_FAILED,
                "limit must be an integer from 1 to " + MAX_LIMIT + ", got '" + value + "'");
    }
}
