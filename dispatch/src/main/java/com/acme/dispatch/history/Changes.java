package com.acme.dispatch.history;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Collects the fields that changed, in the order they are given. */
public final class Changes {

    private final List<Change> list = new ArrayList<>();

    /** Adds the field if its value changed. */
    public Changes field(String field, String from, String to) {
        if (!Objects.equals(from, to)) {
            list.add(new Change(field, from, to));
        }
        return this;
    }

    /** Adds the field if the referenced id changed. */
    public Changes field(String field, UUID from, UUID to) {
        return field(field, text(from), text(to));
    }

    /** Adds the price if its value changed; {@code 1500} and {@code 1500.00} are the same price. */
    public Changes price(String field, BigDecimal from, BigDecimal to) {
        boolean same = from == null ? to == null : to != null && from.compareTo(to) == 0;
        if (!same) {
            list.add(new Change(field, text(from), text(to)));
        }
        return this;
    }

    /** Adds the field even if it did not change: an action records the state it was taken in. */
    public Changes always(String field, String from, String to) {
        list.add(new Change(field, from, to));
        return this;
    }

    public boolean isEmpty() {
        return list.isEmpty();
    }

    public List<Change> list() {
        return List.copyOf(list);
    }

    private static String text(UUID id) {
        return id == null ? null : id.toString();
    }

    private static String text(BigDecimal price) {
        return price == null ? null : price.stripTrailingZeros().toPlainString();
    }
}
