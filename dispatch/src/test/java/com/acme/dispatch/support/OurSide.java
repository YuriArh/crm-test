package com.acme.dispatch.support;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Reads the snapshot of our result kept by a problem ({@code docs/specs/conflict-history.md}). */
public final class OurSide {

    /** No snapshot: the problem has no two sides. */
    public static final String NOT_RECORDED = "not recorded";
    /** A snapshot of no result: we had none when the problem was opened. */
    public static final String NO_RESULT = "-/-/-/-";

    private OurSide() {
    }

    /** {@code status/tracking/price/reason} with {@code -} for empty fields, or {@link #NOT_RECORDED}. */
    public static String of(JdbcClient jdbc, UUID problemId) {
        return jdbc.sql("""
                        SELECT CASE WHEN NOT our_recorded THEN :notRecorded
                                    ELSE coalesce(our_status, '-') || '/' || coalesce(our_tracking_number, '-')
                                         || '/' || coalesce(our_price::text, '-') || '/' || coalesce(our_reason, '-')
                               END
                          FROM problem WHERE id = :id""")
                .param("notRecorded", NOT_RECORDED)
                .param("id", problemId)
                .query(String.class)
                .single();
    }
}
