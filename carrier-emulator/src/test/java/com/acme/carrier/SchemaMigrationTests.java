package com.acme.carrier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import com.acme.carrier.support.IntegrationTest;
import com.acme.carrier.support.TestClockConfiguration;
import com.acme.carrier.support.TestRows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@IntegrationTest
class SchemaMigrationTests {

    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void cleanUp() {
        TestRows.deleteAll(jdbc);
    }

    @Test
    void migrationsAreApplied() {
        assertThat(jdbc.sql("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
                .query(String.class).list()).containsExactly("1", "2");
    }

    @Test
    void validAcceptedAndExecutedRowsAreStored() {
        insert(UUID.randomUUID(), "ACCEPTED", null, null);
        insertExecuted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), false, null);
        insertExecuted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), true,
                TestClockConfiguration.NOW.plusSeconds(60));

        assertThat(jdbc.sql("SELECT count(*) FROM operation").query(Integer.class).single()).isEqualTo(3);
    }

    @Test
    void executionCountColumnIsGone() {
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM information_schema.columns
                         WHERE table_name = 'operation' AND column_name = 'execution_count'""")
                .query(Integer.class).single()).isZero();
    }

    @Test
    void unknownStateIsRejected() {
        assertThatThrownBy(() -> insert(UUID.randomUUID(), "LOST", null, null))
                .hasMessageContaining("operation_state_known");
    }

    @Test
    void executedOperationNeedsAResult() {
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO operation (operation_id, cargo_description, weight_kg, delivery_address, deadline,
                                               payload_hash, state, received_count, received_at, executed_at,
                                               fault_duplicate, fault_delayed, in_progress_message_id, result_message_id)
                        VALUES (:id, 'Pallets', 12.5, 'Main st 1', DATE '2026-12-01', 'h', 'EXECUTED', 1, :now, :now,
                                false, false, :m1, :m2)
                        """)
                .param("id", UUID.randomUUID())
                .param("now", Timestamp.from(TestClockConfiguration.NOW))
                .param("m1", UUID.randomUUID())
                .param("m2", UUID.randomUUID())
                .update())
                .hasMessageContaining("operation_executed_has_result");
        assertThatThrownBy(() -> insert(UUID.randomUUID(), "ACCEPTED", "SUCCEEDED", null))
                .hasMessageContaining("operation_accepted_not_executed");
    }

    @Test
    void executedOperationNeedsBothMessageIds() {
        assertThatThrownBy(() -> insertExecuted(UUID.randomUUID(), null, UUID.randomUUID(), false, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("operation_executed_has_message_ids");
        assertThatThrownBy(() -> insertExecuted(UUID.randomUUID(), UUID.randomUUID(), null, false, null))
                .hasMessageContaining("operation_executed_has_message_ids");
    }

    @Test
    void publishTimeOnlyForADelayedExecutedOperation() {
        assertThatThrownBy(() -> insertExecuted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), false,
                TestClockConfiguration.NOW))
                .hasMessageContaining("operation_publish_at_only_delayed");
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO operation (operation_id, cargo_description, weight_kg, delivery_address, deadline,
                                               payload_hash, state, received_count, received_at, fault_duplicate,
                                               fault_delayed, result_publish_at)
                        VALUES (:id, 'Pallets', 12.5, 'Main st 1', DATE '2026-12-01', 'h', 'ACCEPTED', 1, :now,
                                false, true, :now)
                        """)
                .param("id", UUID.randomUUID())
                .param("now", Timestamp.from(TestClockConfiguration.NOW))
                .update())
                .hasMessageContaining("operation_publish_at_only_delayed");
    }

    @Test
    void faultFlagsAreRequired() {
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO operation (operation_id, cargo_description, weight_kg, delivery_address, deadline,
                                               payload_hash, state, received_count, received_at, fault_duplicate)
                        VALUES (:id, 'Pallets', 12.5, 'Main st 1', DATE '2026-12-01', 'h', 'ACCEPTED', 1, :now, false)
                        """)
                .param("id", UUID.randomUUID())
                .param("now", Timestamp.from(TestClockConfiguration.NOW))
                .update())
                .hasMessageContaining("fault_delayed");
    }

    @Test
    void feedEntryNeedsAKnownOperationAndStatus() {
        assertThatThrownBy(() -> feedEntry(UUID.randomUUID(), 1, "SUCCEEDED"))
                .hasMessageContaining("feed_entry_operation_fk");

        UUID id = UUID.randomUUID();
        insert(id, "ACCEPTED", null, null);
        assertThatThrownBy(() -> feedEntry(id, 1, "DONE")).hasMessageContaining("feed_entry_status_known");
        assertThatThrownBy(() -> feedEntry(id, 0, "IN_PROGRESS")).hasMessageContaining("feed_entry_sequence_positive");
    }

    private void insert(UUID id, String state, String resultStatus, Instant executedAt) {
        jdbc.sql("""
                        INSERT INTO operation (operation_id, cargo_description, weight_kg, delivery_address, deadline,
                                               payload_hash, state, result_status, received_count, received_at,
                                               executed_at, fault_duplicate, fault_delayed)
                        VALUES (:id, 'Pallets', 12.5, 'Main st 1', DATE '2026-12-01', 'h', :state, :result, 1,
                                :now, :executedAt, false, false)
                        """)
                .param("id", id)
                .param("state", state)
                .param("result", resultStatus)
                .param("now", Timestamp.from(TestClockConfiguration.NOW))
                .param("executedAt", executedAt == null ? null : Timestamp.from(executedAt))
                .update();
    }

    private void insertExecuted(UUID id, UUID inProgressId, UUID resultId, boolean delayed, Instant publishAt) {
        jdbc.sql("""
                        INSERT INTO operation (operation_id, cargo_description, weight_kg, delivery_address, deadline,
                                               payload_hash, state, result_status, received_count, received_at,
                                               executed_at, fault_duplicate, fault_delayed, in_progress_message_id,
                                               result_message_id, result_publish_at)
                        VALUES (:id, 'Pallets', 12.5, 'Main st 1', DATE '2026-12-01', 'h', 'EXECUTED', 'SUCCEEDED', 1,
                                :now, :now, false, :delayed, :inProgressId, :resultId, :publishAt)
                        """)
                .param("id", id)
                .param("now", Timestamp.from(TestClockConfiguration.NOW))
                .param("delayed", delayed)
                .param("inProgressId", inProgressId)
                .param("resultId", resultId)
                .param("publishAt", publishAt == null ? null : Timestamp.from(publishAt))
                .update();
    }

    private void feedEntry(UUID operationId, int sequence, String status) {
        jdbc.sql("""
                        INSERT INTO feed_entry (message_id, operation_id, sequence, status, occurred_at)
                        VALUES (:messageId, :operationId, :sequence, :status, :now)
                        """)
                .param("messageId", UUID.randomUUID())
                .param("operationId", operationId)
                .param("sequence", sequence)
                .param("status", status)
                .param("now", Timestamp.from(TestClockConfiguration.NOW))
                .update();
    }
}
