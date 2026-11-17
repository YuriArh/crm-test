package com.acme.carrier.admin;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Demo scenarios on the carrier's data ({@code docs/specs/carrier-scenarios.md}): no other code writes like this. */
@Component
public class ScenarioDao {

    private final JdbcClient jdbc;

    public ScenarioDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Replaces the final result of a settled operation — executed, nothing waiting for a delayed publication — with a
     * new message id; the feed is left alone. Returns {@code false} if there is no such operation.
     */
    boolean changeResult(UUID operationId, AdminResultChange change, UUID messageId) {
        return jdbc.sql("""
                        UPDATE operation
                           SET result_status = :status, tracking_number = :tracking, price = :price, reason = :reason,
                               result_message_id = :messageId
                         WHERE operation_id = :id AND state = 'EXECUTED' AND result_publish_at IS NULL
                        """)
                .param("id", operationId)
                .param("status", change.status().name())
                .param("tracking", change.trackingNumber())
                .param("price", change.price())
                .param("reason", change.reason())
                .param("messageId", messageId)
                .update() == 1;
    }

    /**
     * Deletes the operation and its feed entries; returns how many entries went, or empty if the operation is not
     * known. The row is locked first: the executor publishes under the same lock, so no entry can be added between
     * the two deletes.
     */
    @Transactional
    public Optional<Integer> forget(UUID operationId) {
        boolean known = jdbc.sql("SELECT 1 FROM operation WHERE operation_id = :id FOR UPDATE")
                .param("id", operationId).query(Integer.class).optional().isPresent();
        if (!known) {
            return Optional.empty();
        }
        int entries = jdbc.sql("DELETE FROM feed_entry WHERE operation_id = :id").param("id", operationId).update();
        jdbc.sql("DELETE FROM operation WHERE operation_id = :id").param("id", operationId).update();
        return Optional.of(entries);
    }
}
