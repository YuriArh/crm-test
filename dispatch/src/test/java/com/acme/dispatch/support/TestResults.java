package com.acme.dispatch.support;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import com.acme.dispatch.exchange.inbox.InboxProcessor;
import com.acme.dispatch.exchange.inbox.InboxSource;
import com.acme.dispatch.exchange.inbox.ResultInbox;

import tools.jackson.databind.JsonNode;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivers a carrier result the way the feed poller does: received in a transaction, then processed.
 * Returns what happened to it: {@code DUPLICATE}, or the record's {@code processing_status}.
 */
@TestComponent
public class TestResults {

    private final ResultInbox inbox;
    private final InboxProcessor processor;
    private final TransactionTemplate transaction;
    private final JdbcClient jdbc;

    public TestResults(ResultInbox inbox, InboxProcessor processor, TransactionTemplate transaction, JdbcClient jdbc) {
        this.inbox = inbox;
        this.processor = processor;
        this.transaction = transaction;
        this.jdbc = jdbc;
    }

    public String deliver(JsonNode entry) {
        Optional<UUID> id = receive(entry);
        if (id.isEmpty()) {
            return "DUPLICATE";
        }
        processor.processDue();
        return status(id.get());
    }

    /** Only receives the entry (step 1); returns the new inbox row, or empty for a repeated message. */
    public Optional<UUID> receive(JsonNode entry) {
        return receive(entry, InboxSource.FEED);
    }

    /** Receives the entry as if it came from {@code source}. */
    public Optional<UUID> receive(JsonNode entry, InboxSource source) {
        return transaction.execute(status -> inbox.receive(entry, source));
    }

    public String status(UUID inboxId) {
        return jdbc.sql("SELECT processing_status FROM inbox WHERE id = :id")
                .param("id", inboxId).query(String.class).single();
    }

    /** When the record got its processing outcome, or {@code null}. */
    public Instant processedAt(UUID inboxId) {
        OffsetDateTime value = jdbc.sql("SELECT processed_at FROM inbox WHERE id = :id").param("id", inboxId)
                .query((rs, n) -> rs.getObject(1, OffsetDateTime.class)).list().getFirst();
        return value == null ? null : value.toInstant();
    }
}
