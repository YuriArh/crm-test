package com.acme.dispatch.support;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Removes rows created by tests; reference data (services, users, feed cursor) stays. */
public final class TestRows {

    private TestRows() {
    }

    public static void deleteRequestData(JdbcClient jdbc) {
        jdbc.sql("DELETE FROM idempotency_key").update();
        jdbc.sql("DELETE FROM inbox").update();
        jdbc.sql("DELETE FROM exchange_attempt").update();
        jdbc.sql("DELETE FROM dispatch_operation").update();
        jdbc.sql("DELETE FROM delivery_request").update();
    }
}
