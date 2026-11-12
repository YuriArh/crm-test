package com.acme.dispatch.request;

/**
 * A field in a PATCH body: either not sent (keep the current value) or sent — with a value or with
 * {@code null}, which means "clear".
 */
public record FieldChange<T>(boolean present, T value) {

    private static final FieldChange<?> ABSENT = new FieldChange<>(false, null);

    @SuppressWarnings("unchecked")
    public static <T> FieldChange<T> absent() {
        return (FieldChange<T>) ABSENT;
    }

    public static <T> FieldChange<T> of(T value) {
        return new FieldChange<>(true, value);
    }
}
