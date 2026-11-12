package com.acme.carrier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
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
                .query(String.class).list()).containsExactly("1");
    }

    @Test
    void validAcceptedAndExecutedRowsAreStored() {
        insert(UUID.randomUUID(), "ACCEPTED", 0, null, null);
        insert(UUID.randomUUID(), "EXECUTED", 1, "SUCCEEDED", TestClockConfiguration.NOW);

        assertThat(jdbc.sql("SELECT count(*) FROM operation").query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void operationIsNeverExecutedTwice() {
        assertThatThrownBy(() -> insert(UUID.randomUUID(), "EXECUTED", 2, "SUCCEEDED", TestClockConfiguration.NOW))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("operation_execution_at_most_once");
    }

    @Test
    void unknownStateIsRejected() {
        assertThatThrownBy(() -> insert(UUID.randomUUID(), "LOST", 0, null, null))
                .hasMessageContaining("operation_state_known");
    }

    @Test
    void executedOperationNeedsAResult() {
        assertThatThrownBy(() -> insert(UUID.randomUUID(), "EXECUTED", 1, null, TestClockConfiguration.NOW))
                .hasMessageContaining("operation_executed_has_result");
        assertThatThrownBy(() -> insert(UUID.randomUUID(), "ACCEPTED", 0, "SUCCEEDED", null))
                .hasMessageContaining("operation_accepted_not_executed");
    }

    @Test
    void feedEntryNeedsAKnownOperationAndStatus() {
        assertThatThrownBy(() -> feedEntry(UUID.randomUUID(), 1, "SUCCEEDED"))
                .hasMessageContaining("feed_entry_operation_fk");

        UUID id = UUID.randomUUID();
        insert(id, "ACCEPTED", 0, null, null);
        assertThatThrownBy(() -> feedEntry(id, 1, "DONE")).hasMessageContaining("feed_entry_status_known");
        assertThatThrownBy(() -> feedEntry(id, 0, "IN_PROGRESS")).hasMessageContaining("feed_entry_sequence_positive");
    }

    private void insert(UUID id, String state, int executions, String resultStatus, java.time.Instant executedAt) {
        jdbc.sql("""
                        INSERT INTO operation (operation_id, cargo_description, weight_kg, delivery_address, deadline,
                                               payload_hash, state, result_status, received_count, execution_count,
                                               received_at, executed_at)
                        VALUES (:id, 'Pallets', 12.5, 'Main st 1', DATE '2026-12-01', 'h', :state, :result, 1,
                                :executions, :now, :executedAt)
                        """)
                .param("id", id)
                .param("state", state)
                .param("result", resultStatus)
                .param("executions", executions)
                .param("now", Timestamp.from(TestClockConfiguration.NOW))
                .param("executedAt", executedAt == null ? null : Timestamp.from(executedAt))
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
