package com.acme.dispatch;

import static com.acme.dispatch.support.DemoData.NORTH;
import static com.acme.dispatch.support.DemoData.NORTH_INITIATOR;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import com.acme.dispatch.support.IntegrationTest;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * V12 fills our side of the data that existed before it ({@code docs/specs/conflict-history.md} §2.2): the schema is
 * built up to V11 in a schema of its own, rows are inserted, and then V12 runs on them.
 */
@IntegrationTest
class ConflictSidesMigrationTests {

    static final String SCHEMA = "v12_backfill";
    static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 12, 9, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcClient jdbc;

    UUID request;
    UUID operation;

    @BeforeEach
    void migrateToV11() {
        flyway("11").migrate();
        request = UUID.randomUUID();
        operation = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO v12_backfill.delivery_request (id, service_id, owner_id, cargo_description,
                            weight_kg, delivery_address, deadline, created_at, updated_at, version)
                        VALUES (:id, :service, :owner, 'Pallets', 1, 'Main st 1', :deadline, :now, :now, 0)""")
                .param("id", request)
                .param("service", NORTH)
                .param("owner", NORTH_INITIATOR)
                .param("deadline", LocalDate.of(2026, 11, 20))
                .param("now", NOW)
                .update();
        jdbc.sql("""
                        INSERT INTO v12_backfill.dispatch_operation (request_id, operation_id, payload_hash,
                            next_attempt_at, created_at, updated_at)
                        VALUES (:request, :operation, :hash, :now, :now, :now)""")
                .param("request", request)
                .param("operation", operation)
                .param("hash", "0".repeat(64))
                .param("now", NOW)
                .update();
    }

    @AfterEach
    void dropSchema() {
        jdbc.sql("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE").update();
    }

    @Test
    void v12FillsLocalSideFromKey() {
        UUID succeeded = insertReconciliation("SUCCEEDED|TRK-00000001|1500|");
        UUID rejected = insertReconciliation("REJECTED|||Address | not served");
        UUID none = insertReconciliation(null);

        flyway("12").migrate();

        assertThat(local(succeeded)).containsEntry("local_status", "SUCCEEDED")
                .containsEntry("local_tracking_number", "TRK-00000001")
                .containsEntry("local_price", new BigDecimal("1500.00"))
                .containsEntry("local_reason", null);
        assertThat(local(rejected)).containsEntry("local_status", "REJECTED")
                .containsEntry("local_tracking_number", null)
                .containsEntry("local_price", null)
                .containsEntry("local_reason", "Address | not served");
        assertThat(local(none)).containsEntry("local_status", null)
                .containsEntry("local_tracking_number", null)
                .containsEntry("local_price", null)
                .containsEntry("local_reason", null);
    }

    @Test
    void v12FillsReconciliationConflictFromItsRow() {
        UUID check = insertReconciliation("SUCCEEDED|TRK-00000001|612.5|");
        UUID problem = insertProblem("RECONCILIATION_CONFLICT", null, check);

        flyway("12").migrate();

        assertThat(ourSide(problem)).containsEntry("our_recorded", true)
                .containsEntry("our_status", "SUCCEEDED")
                .containsEntry("our_tracking_number", "TRK-00000001")
                .containsEntry("our_price", new BigDecimal("612.50"))
                .containsEntry("our_reason", null);
    }

    @Test
    void v12LeavesOtherProblemsUnrecorded() {
        UUID inbox = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO v12_backfill.inbox (id, message_id, payload, source, processing_status,
                                                        received_at, operation_id)
                        VALUES (:id, 'msg-1', '{}', 'RECONCILIATION', 'CONFLICT', :now, :operation)""")
                .param("id", inbox)
                .param("now", NOW)
                .param("operation", operation)
                .update();
        UUID resultConflict = insertProblem("RESULT_CONFLICT", inbox, null);
        UUID missing = insertProblem("EXTERNAL_MISSING", null, null);
        UUID delivery = insertProblem("DELIVERY_FAILED", null, null);

        flyway("12").migrate();

        for (UUID problem : new UUID[] {resultConflict, missing, delivery}) {
            assertThat(ourSide(problem)).containsEntry("our_recorded", false)
                    .containsEntry("our_status", null)
                    .containsEntry("our_tracking_number", null)
                    .containsEntry("our_price", null)
                    .containsEntry("our_reason", null);
        }
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .createSchemas(true)
                .target(target)
                .load();
    }

    private UUID insertReconciliation(String localResult) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO v12_backfill.reconciliation (id, request_id, operation_id, checked_at, run_type,
                            outcome, acknowledged, delivery_status, local_result, carrier_state, details)
                        VALUES (:id, :request, :operation, :now, 'SCHEDULED', 'CONFLICT', false, 'ACKNOWLEDGED',
                                :local, 'ACCEPTED', 'why')""")
                .param("id", id)
                .param("request", request)
                .param("operation", operation)
                .param("now", NOW)
                .param("local", localResult)
                .update();
        return id;
    }

    private UUID insertProblem(String kind, UUID inboxId, UUID reconciliationId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO v12_backfill.problem (id, kind, status, request_id, inbox_id, reconciliation_id,
                                                          details, opened_at)
                        VALUES (:id, :kind, 'OPEN', :request, :inbox, :reconciliation, 'why', :now)""")
                .param("id", id)
                .param("kind", kind)
                .param("request", request)
                .param("inbox", inboxId)
                .param("reconciliation", reconciliationId)
                .param("now", NOW)
                .update();
        return id;
    }

    private Map<String, Object> local(UUID reconciliationId) {
        return jdbc.sql("""
                        SELECT local_status, local_tracking_number, local_price, local_reason
                          FROM v12_backfill.reconciliation WHERE id = :id""")
                .param("id", reconciliationId)
                .query()
                .singleRow();
    }

    private Map<String, Object> ourSide(UUID problemId) {
        return jdbc.sql("""
                        SELECT our_recorded, our_status, our_tracking_number, our_price, our_reason
                          FROM v12_backfill.problem WHERE id = :id""")
                .param("id", problemId)
                .query()
                .singleRow();
    }
}
