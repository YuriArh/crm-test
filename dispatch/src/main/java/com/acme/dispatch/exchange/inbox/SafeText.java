package com.acme.dispatch.exchange.inbox;

/**
 * Text taken from a carrier record or an exception, made safe to store and log: control characters are written as
 * {@code \}{@code uXXXX} (the database refuses NUL in any text column) and the length is bounded.
 */
final class SafeText {

    static final int MAX_LENGTH = 500;

    private SafeText() {
    }

    static String of(String value) {
        return of(value, MAX_LENGTH);
    }

    static String of(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        StringBuilder text = new StringBuilder(Math.min(value.length(), maxLength + 1));
        for (int i = 0; i < value.length() && text.length() <= maxLength; i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                text.append(String.format("\\u%04X", (int) c));
            } else {
                text.append(c);
            }
        }
        return text.length() > maxLength ? text.substring(0, maxLength - 1) + "…" : text.toString();
    }

    /** Whether the value has no control characters at all. */
    static boolean isPrintable(String value) {
        return value.chars().noneMatch(c -> c < 0x20 || c == 0x7F);
    }
}
