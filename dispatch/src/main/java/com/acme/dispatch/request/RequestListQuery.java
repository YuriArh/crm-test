package com.acme.dispatch.request;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import com.acme.dispatch.common.web.ApiException;
import com.acme.dispatch.common.web.ErrorCode;
import com.acme.dispatch.exchange.DeliveryStatus;

/**
 * Validated parameters of the registry query. Parameters arrive as raw strings so that every
 * malformed value gets the same {@code VALIDATION_FAILED} answer with a clear message.
 * Sort fields come from a whitelist, so nothing from the request reaches SQL as text.
 *
 * @param businessStatus filter, or {@code null}
 * @param deliveryStatus filter, or {@code null}
 * @param overdue        filter, or {@code null}
 */
public record RequestListQuery(
        BusinessStatus businessStatus,
        DeliveryStatus deliveryStatus,
        Boolean overdue,
        int page,
        int size,
        SortField sortField,
        boolean ascending) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;
    public static final String DEFAULT_SORT = "createdAt,desc";

    /** Sortable fields: API name → SQL column. */
    public enum SortField {
        CREATED_AT("createdAt", "r.created_at"),
        DEADLINE("deadline", "r.deadline"),
        NUMBER("number", "r.number");

        private static final Map<String, SortField> BY_API_NAME = Arrays.stream(values())
                .collect(Collectors.toUnmodifiableMap(SortField::apiName, field -> field));

        private final String apiName;
        private final String column;

        SortField(String apiName, String column) {
            this.apiName = apiName;
            this.column = column;
        }

        public String apiName() {
            return apiName;
        }

        public String column() {
            return column;
        }
    }

    public static RequestListQuery defaults() {
        return parse(null, null, null, null, null, null);
    }

    public static RequestListQuery parse(String businessStatus, String deliveryStatus, String overdue,
                                         String page, String size, String sort) {
        BusinessStatus business = parseEnum(BusinessStatus.class, "businessStatus", businessStatus);
        DeliveryStatus delivery = parseEnum(DeliveryStatus.class, "deliveryStatus", deliveryStatus);
        Boolean overdueFilter = parseBoolean("overdue", overdue);

        int pageNumber = parseInt("page", page, 0);
        if (pageNumber < 0) {
            throw invalid("page must be 0 or greater, got " + pageNumber);
        }
        int pageSize = parseInt("size", size, DEFAULT_SIZE);
        if (pageSize < 1 || pageSize > MAX_SIZE) {
            throw invalid("size must be between 1 and " + MAX_SIZE + ", got " + pageSize);
        }

        String sortValue = isBlank(sort) ? DEFAULT_SORT : sort.trim();
        String[] parts = sortValue.split(",", -1);
        if (parts.length > 2) {
            throw invalid("sort must look like 'field,asc|desc', got '" + sortValue + "'");
        }
        SortField field = SortField.BY_API_NAME.get(parts[0].trim());
        if (field == null) {
            throw invalid("sort field must be one of " + Arrays.stream(SortField.values())
                    .map(SortField::apiName).toList() + ", got '" + parts[0].trim() + "'");
        }
        boolean ascending = true;
        if (parts.length == 2) {
            String direction = parts[1].trim().toLowerCase(Locale.ROOT);
            ascending = switch (direction) {
                case "asc" -> true;
                case "desc" -> false;
                default -> throw invalid("sort direction must be 'asc' or 'desc', got '" + parts[1].trim() + "'");
            };
        }

        return new RequestListQuery(business, delivery, overdueFilter, pageNumber, pageSize, field, ascending);
    }

    public long offset() {
        return (long) page * size;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String name, String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim());
        } catch (IllegalArgumentException e) {
            throw invalid(name + " must be one of " + Arrays.toString(type.getEnumConstants())
                    + ", got '" + value + "'");
        }
    }

    private static Boolean parseBoolean(String name, String value) {
        if (isBlank(value)) {
            return null;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> throw invalid(name + " must be 'true' or 'false', got '" + value + "'");
        };
    }

    private static int parseInt(String name, String value, int defaultValue) {
        if (isBlank(value)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw invalid(name + " must be an integer, got '" + value + "'");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static ApiException invalid(String detail) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, detail);
    }
}
