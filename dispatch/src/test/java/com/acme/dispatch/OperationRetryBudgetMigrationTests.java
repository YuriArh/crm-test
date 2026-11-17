package com.acme.dispatch;

import static com.acme.dispatch.support.DemoData.NORTH;
import static com.acme.dispatch.support.DemoData.NORTH_INITIATOR;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import javax.sql.DataSource;

import com.acme.dispatch.support.IntegrationTest;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * V10 adds the retry budget and the applied record to the operation. The applied record of existing operations is
 * filled from their history: the migrations are run up to V9 in a separate schema, the state is stored, then V10 is
 * applied.
 */
@IntegrationTest
class OperationRetryBudgetMigrationTests {

    private static final String SCHEMA = "v10_retry_budget_check";

    @Autowired
    DataSource dataSource;
    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void dropSchema() {
        jdbc.sql("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE").update();
    }

    @Test
    void appliedRecordIsBackfilled() {
        migrate("9");
        UUID requestId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        UUID untouchedRequest = UUID.randomUUID();
        insertOperation(requestId, operationId, 3);
        insertOperation(untouchedRequest, UUID.randomUUID(), 1);
        record(operationId, 1, "APPLIED");
        UUID newest = record(operationId, 2, "APPLIED");
        record(operationId, 2, "STALE");
        record(operationId, 3, "CONFLICT");

        migrate("10");

        assertThat(jdbc.sql("SELECT applied_inbox_id FROM " + SCHEMA + ".dispatch_operation WHERE request_id = :id")
                .param("id", requestId).query(UUID.class).single()).isEqualTo(newest);
        assertThat(jdbc.sql("SELECT count(*) FROM " + SCHEMA + ".dispatch_operation WHERE request_id = :id"
                        + " AND applied_inbox_id IS NULL")
                .param("id", untouchedRequest).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM " + SCHEMA + ".dispatch_operation WHERE retry_from = 0")
                .query(Long.class).single()).isEqualTo(2);
    }

    private void insertOperation(UUID requestId, UUID operationId, int attempts) {
        jdbc.sql("""
                        INSERT INTO %s.delivery_request (id, service_id, owner_id, cargo_description, weight_kg,
                            delivery_address, deadline, created_at, updated_at, version)
                        VALUES (:id, :service, :owner, 'Pallets', 1, 'Main st 1', DATE '2026-11-20', now(), now(), 0)"""
                        .formatted(SCHEMA))
                .param("id", requestId)
                .param("service", NORTH)
                .param("owner", NORTH_INITIATOR)
                .update();
        jdbc.sql("""
                        INSERT INTO %s.dispatch_operation (request_id, operation_id, payload_hash, attempts,
                            next_attempt_at, created_at, updated_at)
                        VALUES (:id, :operation, :hash, :attempts, now(), now(), now())""".formatted(SCHEMA))
                .param("id", requestId)
                .param("operation", operationId)
                .param("hash", "0".repeat(64))
                .param("attempts", attempts)
                .update();
    }

    private UUID record(UUID operationId, int sequence, String status) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO %s.inbox (id, payload, source, processing_status, operation_id, sequence,
                                              received_at)
                        VALUES (:id, '{}', 'RECONCILIATION', :status, :operation, :sequence, now())"""
                        .formatted(SCHEMA))
                .param("id", id)
                .param("status", status)
                .param("operation", operationId)
                .param("sequence", sequence)
                .update();
        return id;
    }

    private void migrate(String target) {
        Flyway.configure()
                .dataSource(dataSource)
                .schemas(SCHEMA)
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
    }
}
