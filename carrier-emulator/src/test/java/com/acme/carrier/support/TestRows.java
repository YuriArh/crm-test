package com.acme.carrier.support;

import org.springframework.jdbc.core.simple.JdbcClient;

public final class TestRows {

    private TestRows() {
    }

    /** Removes all operations and feed entries; call in {@code @AfterEach}. */
    public static void deleteAll(JdbcClient jdbc) {
        jdbc.sql("DELETE FROM feed_entry").update();
        jdbc.sql("DELETE FROM operation").update();
    }
}
