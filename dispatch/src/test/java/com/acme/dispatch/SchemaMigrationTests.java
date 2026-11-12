package com.acme.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** Verifies that migrations apply and that the database itself enforces integrity (NF03). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SchemaMigrationTests {

    static final UUID NORTH = UUID.fromString("0195f000-0000-7000-8000-000000000001");
    static final UUID SOUTH = UUID.fromString("0195f000-0000-7000-8000-000000000002");
    static final UUID NORTH_INITIATOR = UUID.fromString("0195f000-0000-7000-8000-000000000101");
    static final UUID NORTH_OPERATOR = UUID.fromString("0195f000-0000-7000-8000-000000000102");
    static final UUID SOUTH_INITIATOR = UUID.fromString("0195f000-0000-7000-8000-000000000201");

    static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 12, 9, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    Flyway flyway;

    @Autowired
    JdbcClient jdbc;

    @Test
    void allMigrationsAreApplied() {
        assertThat(Arrays.stream(flyway.info().applied()).map(MigrationInfo::getVersion).map(Object::toString))
                .containsExactly("1", "2", "3", "4", "5");
    }

    @Test
    void demoReferenceDataIsSeeded() {
        assertThat(count("SELECT count(*) FROM service")).isEqualTo(2);
        assertThat(jdbc.sql("SELECT username FROM app_user ORDER BY username").query(String.class).list())
                .containsExactly("north.initiator", "north.operator", "south.initiator");
        assertThat(count("SELECT count(*) FROM feed_cursor WHERE name = 'carrier' AND last_feed_seq = 0"))
                .isEqualTo(1);
    }

    @Test
    void requestWithValidDataIsAccepted() {
        UUID id = insertRequest(NORTH, NORTH_INITIATOR, NORTH_OPERATOR, new BigDecimal("12.5"));

        Long number = jdbc.sql("SELECT number FROM delivery_request WHERE id = :id")
                .param("id", id).query(Long.class).single();
        assertThat(number).isPositive();
    }

    @Test
    void assigneeFromAnotherServiceIsRejected() {
        // The south initiator is not a member of the north service.
        assertThatThrownBy(() -> insertRequest(NORTH, NORTH_INITIATOR, SOUTH_INITIATOR, new BigDecimal("1.0")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("delivery_request_assignee_same_service");
    }

    @Test
    void ownerFromAnotherServiceIsRejected() {
        assertThatThrownBy(() -> insertRequest(SOUTH, NORTH_INITIATOR, null, new BigDecimal("1.0")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("delivery_request_owner_same_service");
    }

    @Test
    void weightOutOfRangeIsRejected() {
        assertThatThrownBy(() -> insertRequest(NORTH, NORTH_INITIATOR, null, BigDecimal.ZERO))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("delivery_request_weight_range");
    }

    @Test
    void succeededWithoutTrackingNumberIsRejected() {
        UUID id = insertRequest(NORTH, NORTH_INITIATOR, null, new BigDecimal("3.0"));

        assertThatThrownBy(() -> jdbc.sql("""
                        UPDATE delivery_request
                        SET business_status = 'SUCCEEDED', price = 100, result_at = :now
                        WHERE id = :id""")
                .param("now", NOW).param("id", id).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("delivery_request_succeeded_has_result");
    }

    @Test
    void operationIdIsUnique() {
        UUID operationId = UUID.randomUUID();
        insertOperation(insertRequest(NORTH, NORTH_INITIATOR, null, new BigDecimal("1.0")), operationId);
        UUID secondRequest = insertRequest(NORTH, NORTH_INITIATOR, null, new BigDecimal("2.0"));

        assertThatThrownBy(() -> insertOperation(secondRequest, operationId))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void repeatedInboxMessageIncrementsDuplicateCounter() {
        insertInboxMessage("msg-1");
        insertInboxMessage("msg-1");

        assertThat(count("SELECT count(*) FROM inbox WHERE message_id = 'msg-1'")).isEqualTo(1);
        assertThat(count("SELECT duplicate_count FROM inbox WHERE message_id = 'msg-1'")).isEqualTo(1);
    }

    private UUID insertRequest(UUID serviceId, UUID ownerId, UUID assigneeId, BigDecimal weight) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO delivery_request (id, service_id, owner_id, assignee_id, cargo_description,
                            weight_kg, delivery_address, deadline, created_at, updated_at, version)
                        VALUES (:id, :service, :owner, :assignee, 'Pallets', :weight, 'Main st 1', :deadline,
                            :now, :now, 0)""")
                .param("id", id)
                .param("service", serviceId)
                .param("owner", ownerId)
                .param("assignee", assigneeId)
                .param("weight", weight)
                .param("deadline", LocalDate.of(2026, 11, 20))
                .param("now", NOW)
                .update();
        return id;
    }

    private void insertOperation(UUID requestId, UUID operationId) {
        jdbc.sql("""
                        INSERT INTO dispatch_operation (request_id, operation_id, payload_hash, next_attempt_at,
                            created_at, updated_at)
                        VALUES (:request, :operation, :hash, :now, :now, :now)""")
                .param("request", requestId)
                .param("operation", operationId)
                .param("hash", "0".repeat(64))
                .param("now", NOW)
                .update();
    }

    private void insertInboxMessage(String messageId) {
        jdbc.sql("""
                        INSERT INTO inbox (id, message_id, payload, source, processing_status, received_at)
                        VALUES (:id, :message, '{}'::jsonb, 'FEED', 'APPLIED', :now)
                        ON CONFLICT (message_id) WHERE message_id IS NOT NULL
                        DO UPDATE SET duplicate_count = inbox.duplicate_count + 1, last_duplicate_at = :now""")
                .param("id", UUID.randomUUID())
                .param("message", messageId)
                .param("now", NOW)
                .update();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
