package com.acme.carrier.feed;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import com.acme.carrier.operation.ResultStatus;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Result feed: {@code GET /results?after=N} returns entries with {@code feedSeq > N} in ascending order. */
@RestController
public class FeedController {

    private final JdbcClient jdbc;

    public FeedController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/results")
    Page results(@RequestParam(defaultValue = "0") @Min(0) long after,
                 @RequestParam(defaultValue = "100") @Min(1) @Max(100) int limit) {
        List<ResultEntry> items = jdbc.sql("""
                        SELECT feed_seq, message_id, operation_id, sequence, status, tracking_number, price, reason,
                               occurred_at
                          FROM feed_entry
                         WHERE feed_seq > :after
                         ORDER BY feed_seq
                         LIMIT :limit
                        """)
                .param("after", after)
                .param("limit", limit)
                .query((rs, row) -> new ResultEntry(
                        rs.getLong("feed_seq"),
                        rs.getObject("message_id", UUID.class),
                        rs.getObject("operation_id", UUID.class),
                        rs.getInt("sequence"),
                        ResultStatus.valueOf(rs.getString("status")),
                        rs.getString("tracking_number"),
                        rs.getBigDecimal("price"),
                        rs.getString("reason"),
                        rs.getTimestamp("occurred_at").toInstant()))
                .list();
        return new Page(items);
    }

    record Page(List<ResultEntry> items) {
    }
}
