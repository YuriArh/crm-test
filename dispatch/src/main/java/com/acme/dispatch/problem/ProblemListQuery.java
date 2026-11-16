package com.acme.dispatch.problem;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.acme.dispatch.common.web.ApiException;
import com.acme.dispatch.common.web.ErrorCode;

/**
 * Filters and page of {@code GET /api/problems}.
 *
 * @param status {@code null} for every status ({@code ALL})
 * @param kinds  empty for every kind
 */
public record ProblemListQuery(ProblemStatus status, Set<ProblemKind> kinds, int page, int size) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public ProblemListQuery {
        kinds = kinds.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(kinds));
    }

    public static ProblemListQuery parse(String status, List<String> kinds, String page, String size) {
        ProblemStatus statusFilter = parseStatus(status);
        EnumSet<ProblemKind> kindFilter = EnumSet.noneOf(ProblemKind.class);
        for (String kind : kinds == null ? List.<String>of() : kinds) {
            try {
                kindFilter.add(ProblemKind.valueOf(kind.trim()));
            } catch (IllegalArgumentException e) {
                throw invalid("kind must be one of " + Arrays.toString(ProblemKind.values()) + ", got '" + kind + "'");
            }
        }
        int pageNumber = parseInt("page", page, 0);
        if (pageNumber < 0) {
            throw invalid("page must be 0 or greater, got " + pageNumber);
        }
        int pageSize = parseInt("size", size, DEFAULT_SIZE);
        if (pageSize < 1 || pageSize > MAX_SIZE) {
            throw invalid("size must be between 1 and " + MAX_SIZE + ", got " + pageSize);
        }
        return new ProblemListQuery(statusFilter, kindFilter, pageNumber, pageSize);
    }

    public long offset() {
        return (long) page * size;
    }

    private static ProblemStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return ProblemStatus.OPEN;
        }
        return switch (value.trim()) {
            case "OPEN" -> ProblemStatus.OPEN;
            case "RESOLVED" -> ProblemStatus.RESOLVED;
            case "ALL" -> null;
            default -> throw invalid("status must be one of [OPEN, RESOLVED, ALL], got '" + value + "'");
        };
    }

    private static int parseInt(String name, String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw invalid(name + " must be an integer, got '" + value + "'");
        }
    }

    private static ApiException invalid(String detail) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, detail);
    }
}
