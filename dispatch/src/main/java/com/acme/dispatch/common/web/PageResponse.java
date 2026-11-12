package com.acme.dispatch.common.web;

import java.util.List;

/** One page of a list endpoint with paging metadata. */
public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> of(List<T> items, int page, int size, long totalElements) {
        int totalPages = (int) ((totalElements + size - 1) / size);
        return new PageResponse<>(List.copyOf(items), page, size, totalElements, totalPages);
    }
}
