package com.acme.dispatch;

import static com.acme.dispatch.support.DemoData.NORTH;
import static com.acme.dispatch.support.DemoData.NORTH_INITIATOR;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import com.acme.dispatch.support.IntegrationTest;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * V9 creates {@code problem} and opens problems for the state that already needs an operator: a new table does not
 * fill itself (expand / migrate / contract). The migrations are run up to V8 in a separate schema, the state is
 * stored, then V9 is applied.
 */
@IntegrationTest
class ProblemsMigrationTests {

    private static final String SCHEMA = "v9_problems_check";

    @Autowired
    DataSource dataSource;
    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void dropSchema() {
        jdbc.sql("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE").update();
    }

    @Test
    void existingStateGetsItsProblems() {
        migrate("8");
        UUID unknown = request("UNKNOWN", "Read timed out");
        UUID failed = request("FAILED", "Attempts exhausted (8): Connection refused");
        UUID acknowledged = request("ACKNOWLEDGED", null);
        UUID invalidKnown = record("INVALID", operationOf(acknowledged), "price is out of range");
        UUID invalidUnknown = record("INVALID", UUID.randomUUID(), "Unknown operationId");
        UUID invalidWithoutOperation = record("INVALID", null, "messageId is missing or malformed");
        UUID failedRecord = record("FAILED", operationOf(unknown), "Processing failed 5 times");
        UUID conflict = record("CONFLICT", operationOf(failed), "Sequence 2 was already applied with different content");
        record("APPLIED", operationOf(acknowledged), null);

        migrate("9");

        assertThat(problems()).containsExactlyInAnyOrder(
                "DELIVERY_UNKNOWN|" + unknown + "|-|Read timed out",
                "DELIVERY_FAILED|" + failed + "|-|Attempts exhausted (8): Connection refused",
                "INVALID_MESSAGE|" + acknowledged + "|" + invalidKnown + "|price is out of range",
                "INVALID_MESSAGE|-|" + invalidUnknown + "|Unknown operationId",
                "INVALID_MESSAGE|-|" + invalidWithoutOperation + "|messageId is missing or malformed",
                "MESSAGE_FAILED|" + unknown + "|" + failedRecord + "|Processing failed 5 times",
                "RESULT_CONFLICT|" + failed + "|" + conflict + "|Sequence 2 was already applied with different content");
        assertThat(jdbc.sql("SELECT count(*) FROM " + SCHEMA + ".problem WHERE status = 'OPEN' AND opened_at IS NOT NULL")
                .query(Long.class).single()).isEqualTo(7);
    }

    private List<String> problems() {
        return jdbc.sql("""
                        SELECT kind || '|' || coalesce(request_id::text, '-') || '|' || coalesce(inbox_id::text, '-')
                               || '|' || details
                          FROM %s.problem""".formatted(SCHEMA))
                .query(String.class).list();
    }

    private UUID request(String deliveryStatus, String lastError) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO %s.delivery_request (id, service_id, owner_id, cargo_description, weight_kg,
                            delivery_address, deadline, created_at, updated_at, version)
                        VALUES (:id, :service, :owner, 'Pallets', 1, 'Main st 1', DATE '2026-11-20', now(), now(), 0)"""
                        .formatted(SCHEMA))
                .param("id", id)
                .param("service", NORTH)
                .param("owner", NORTH_INITIATOR)
                .update();
        jdbc.sql("""
                        INSERT INTO %s.dispatch_operation (request_id, operation_id, payload_hash, delivery_status,
                            attempts, next_attempt_at, last_error, acknowledged_at, acknowledged_via, created_at,
                            updated_at)
                        VALUES (:id, :operation, :hash, :status, 1, now(), :error, :ackAt, :ackVia, now(), now())"""
                        .formatted(SCHEMA))
                .param("id", id)
                .param("operation", UUID.randomUUID())
                .param("hash", "0".repeat(64))
                .param("status", deliveryStatus)
                .param("error", lastError)
                .param("ackAt", "ACKNOWLEDGED".equals(deliveryStatus) ? java.sql.Timestamp.valueOf("2026-11-12 09:00:00") : null)
                .param("ackVia", "ACKNOWLEDGED".equals(deliveryStatus) ? "RESPONSE" : null)
                .update();
        return id;
    }

    private UUID operationOf(UUID requestId) {
        return jdbc.sql("SELECT operation_id FROM " + SCHEMA + ".dispatch_operation WHERE request_id = :id")
                .param("id", requestId)
                .query(UUID.class).single();
    }

    private UUID record(String status, UUID operationId, String reason) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO %s.inbox (id, payload, source, processing_status, operation_id, reason,
                                              received_at)
                        VALUES (:id, '{}', 'RECONCILIATION', :status, :operation, :reason, now())"""
                        .formatted(SCHEMA))
                .param("id", id)
                .param("status", status)
                .param("operation", operationId)
                .param("reason", reason)
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
