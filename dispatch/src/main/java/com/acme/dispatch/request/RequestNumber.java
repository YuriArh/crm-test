package com.acme.dispatch.request;

/** Human-readable request number, e.g. {@code DR-000123}. */
public final class RequestNumber {

    private RequestNumber() {
    }

    public static String format(long number) {
        if (number <= 0) {
            throw new IllegalArgumentException("Request number must be positive: " + number);
        }
        return "DR-%06d".formatted(number);
    }
}
