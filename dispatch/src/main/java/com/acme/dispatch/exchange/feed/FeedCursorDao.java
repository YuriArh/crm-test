package com.acme.dispatch.exchange.feed;

import static com.acme.dispatch.common.SqlTime.toSql;

import java.time.Instant;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The read position in the carrier's result feed ({@code feed_cursor}, row {@code carrier}). */
@Repository
public class FeedCursorDao {

    static final String CARRIER = "carrier";

    private final JdbcClient jdbc;

    public FeedCursorDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long read() {
        return jdbc.sql("SELECT last_feed_seq FROM feed_cursor WHERE name = :name")
                .param("name", CARRIER)
                .query(Long.class)
                .single();
    }

    /** Reads the cursor and locks its row until the transaction ends, so only one poller moves it. */
    long lock() {
        return jdbc.sql("SELECT last_feed_seq FROM feed_cursor WHERE name = :name FOR UPDATE")
                .param("name", CARRIER)
                .query(Long.class)
                .single();
    }

    void move(long lastFeedSeq, Instant now) {
        jdbc.sql("UPDATE feed_cursor SET last_feed_seq = :seq, updated_at = :now WHERE name = :name")
                .param("seq", lastFeedSeq)
                .param("now", toSql(now))
                .param("name", CARRIER)
                .update();
    }
}
