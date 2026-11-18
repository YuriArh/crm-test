package com.acme.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.acme.dispatch.support.DemoData.NORTH;
import static com.acme.dispatch.support.DemoData.NORTH_INITIATOR;
import static com.acme.dispatch.support.DemoData.NORTH_OPERATOR;
import static com.acme.dispatch.support.DemoData.SOUTH;
import static com.acme.dispatch.support.DemoData.SOUTH_INITIATOR;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import com.acme.dispatch.support.IntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** Verifies that migrations apply and that the database itself enforces integrity (NF03). */
@IntegrationTest
@Transactional
class SchemaMigrationTests {

    static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 12, 9, 0, 0, 0, ZoneOffset.UTC);

    static final String CHANGE = "[{\"field\": \"comment\", \"from\": null, \"to\": \"Call first\"}]";

    @Autowired
    Flyway flyway;

    @Autowired
    JdbcClient jdbc;

    @Test
    void allMigrationsAreApplied() {
        assertThat(Arrays.stream(flyway.info().applied()).map(MigrationInfo::getVersion).map(Object::toString))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14");
    }

    @Test
    void requestEventIsAccepted() {
        UUID request = insertRequest(NORTH, NORTH_INITIATOR, null, BigDecimal.ONE);
        UUID record = insertInboxRecord("APPLIED");
        UUID problem = insertProblem("RESULT_CONFLICT", "OPEN", request, insertInboxRecord("CONFLICT"), null, null,
                null);

        insertEvent(request, "EDITED", 1L, NORTH_INITIATOR, null, null, CHANGE);
        insertEvent(request, "RESULT_CHANGED", 2L, null, record, null, CHANGE);
        insertEvent(request, "RESULT_CHANGED", 3L, NORTH_OPERATOR, record, problem, CHANGE);
        insertEvent(request, "DELIVERY_RETRIED", null, NORTH_OPERATOR, null, null, CHANGE);

        assertThat(count("SELECT count(*) FROM request_event")).isEqualTo(4);
    }

    @Test
    void requestEventKindIsChecked() {
        UUID request = insertRequest(NORTH, NORTH_INITIATOR, null, BigDecimal.ONE);

        assertThatThrownBy(() -> insertEvent(request, "DELETED", 1L, NORTH_INITIATOR, null, null, CHANGE))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("request_event_kind_valid");
    }

    @Test
    void editNeedsVersion() {
        UUID request = insertRequest(NORTH, NORTH_INITIATOR, null, BigDecimal.ONE);

        assertThatThrownBy(() -> insertEvent(request, "EDITED", null, NORTH_INITIATOR, null, null, CHANGE))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("request_event_version_matches_kind");
    }

    @Test
    void retryHasNoVersion() {
        UUID request = insertRequest(NORTH, NORTH_INITIATOR, null, BigDecimal.ONE);

        assertThatThrownBy(() -> insertEvent(request, "DELIVERY_RETRIED", 1L, NORTH_OPERATOR, null, null, CHANGE))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("request_event_version_matches_kind");
    }

    @Test
    void requestEventVersionIsPositive() {
        UUID request = insertRequest(NORTH, NORTH_INITIATOR, null, BigDecimal.ONE);

        assertThatThrownBy(() -> insertEvent(request, "EDITED", 0L, NORTH_INITIATOR, null, null, CHANGE))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("request_event_version_positive");
    }

    @Test
    void requestEventVersionIsUniquePerRequest() {
        UUID request = insertRequest(NORTH, NORTH_INITIATOR, null, BigDecimal.ONE);
        UUID other = insertRequest(NORTH, NORTH_INITIATOR, null, BigDecimal.ONE);
        insertEvent(request, "EDITED", 1L, NORTH_INITIATOR, null, null, CHANGE);
        insertEvent(other, "EDITED", 1L, NORTH_INITIATOR, null, null, CHANGE);

        assertThatThrownBy(() -> insertEvent(request, "EDITED", 1L, NORTH_OPERATOR, null, null, CHANGE))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("request_event_request_version_unique");
    }

    @Test
    void requestEventChangesAreArray() {
        UUID request = insertRequest(NORTH, NORTH_INITIATOR, null, BigDecimal.ONE);

        assertThatThrownBy(() -> insertEvent(request, "EDITED", 1L, NORTH_INITIATOR, null, null, "{}"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("request_event_changes_array");
    }

    @Test
    void requestEventChangesAreNotEmpty() {
        UUID request = insertRequest(NORTH, NORTH_INITIATOR, null, BigDecimal.ONE);

        assertThatThrownBy(() -> insertEvent(request, "EDITED", 1L, NORTH_INITIATOR, null, null, "[]"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("request_event_changes_array");
    }

    @Test
    void editNeedsActor() {
        UUID request = insertRequest(NORTH, NORTH_INITIATOR, null, BigDecimal.ONE);

        assertThatThrownBy(() -> insertEvent(request, "EDITED", 1L, null, null, null, CHANGE))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("request_event_actor_required");
    }

    @Test
    void retryNeedsActor() {
        UUID request = insertRequest(NORTH, NORTH_INITIATOR, null, BigDecimal.ONE);

        assertThatThrownBy(() -> insertEvent(request, "DELIVERY_RETRIED", null, null, null, null, CHANGE))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("request_event_actor_required");
    }

    @Test
    void resultChangeNeedsRecord() {
        UUID request = insertRequest(NORTH, NORTH_INITIATOR, null, BigDecimal.ONE);

        assertThatThrownBy(() -> insertEvent(request, "RESULT_CHANGED", 1L, null, null, null, CHANGE))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("request_event_result_has_record");
    }

    @Test
    void editHasNoRecord() {
        UUID request = insertRequest(NORTH, NORTH_INITIATOR, null, BigDecimal.ONE);
        UUID record = insertInboxRecord("APPLIED");

        assertThatThrownBy(() -> insertEvent(request, "EDITED", 1L, NORTH_INITIATOR, record, null, CHANGE))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("request_event_result_has_record");
    }

    @Test
    void retryHasNoProblem() {
        UUID request = insertRequest(NORTH, NORTH_INITIATOR, null, BigDecimal.ONE);
        UUID problem = insertProblem("RESULT_CONFLICT", "OPEN", request, insertInboxRecord("CONFLICT"), null, null,
                null);

        assertThatThrownBy(() -> insertEvent(request, "DELIVERY_RETRIED", null, NORTH_OPERATOR, null, problem,
                CHANGE))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("request_event_result_has_record");
    }

    @Test
    void receivedRecordIsNotProcessed() {
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO inbox (id, feed_seq, payload, source, processing_status, next_attempt_at,
                                           received_at, processed_at)
                        VALUES (:id, 1, '{}', 'FEED', 'RECEIVED', :now, :now, :now)""")
                .param("id", UUID.randomUUID())
                .param("now", NOW)
                .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("inbox_received_not_processed");
    }

    @Test
    void processedRecordMayLackProcessedAt() {
        insertInboxRecord("APPLIED");

        assertThat(count("SELECT count(*) FROM inbox WHERE processed_at IS NULL")).isEqualTo(1);
    }

    @Test
    void requestEventIndexExists() {
        assertThat(jdbc.sql("SELECT indexdef FROM pg_indexes WHERE indexname = 'request_event_request_idx'")
                .query(String.class).single()).contains("(request_id, occurred_at)");
    }

    @Test
    void twoSidedProblemKeepsOurResult() {
        UUID request = insertRequest(NORTH, NORTH_INITIATOR, null, BigDecimal.ONE);

        insertProblemWithOurSide("EXTERNAL_MISSING", request, true, "SUCCEEDED", "TRK-00000001",
                new BigDecimal("612.50"), null);

        assertThat(count("SELECT count(*) FROM problem WHERE our_recorded AND our_status = 'SUCCEEDED'"))
                .isEqualTo(1);
    }

    @Test
    void twoSidedProblemMayRecordThatWeHadNoResult() {
        UUID request = insertRequest(NORTH, NORTH_INITIATOR, null, BigDecimal.ONE);

        insertProblemWithOurSide("EXTERNAL_MISSING", request, true, null, null, null, null);

        assertThat(count("SELECT count(*) FROM problem WHERE our_recorded AND our_status IS NULL")).isEqualTo(1);
    }

    @Test
    void problemOurStatusMustBeKnown() {
        UUID request = insertRequest(NORTH, NORTH_INITIATOR, null, BigDecimal.ONE);

        assertThatThrownBy(() -> insertProblemWithOurSide("EXTERNAL_MISSING", request, true, "DONE", null, null,
                null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("problem_our_status_valid");
    }

    @Test
    void problemOurSucceededNeedsPrice() {
        UUID request = insertRequest(NORTH, NORTH_INITIATOR, null, BigDecimal.ONE);

        assertThatThrownBy(() -> insertProblemWithOurSide("EXTERNAL_MISSING", request, true, "SUCCEEDED",
                "TRK-00000001", null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("problem_our_result_complete");
    }

    @Test
    void problemOurRejectedNeedsReason() {
        UUID request = insertRequest(NORTH, NORTH_INITIATOR, null, BigDecimal.ONE);

        assertThatThrownBy(() -> insertProblemWithOurSide("EXTERNAL_MISSING", request, true, "REJECTED", null, null,
                null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("problem_our_result_complete");
    }

    @Test
    void problemOurFieldsNeedStatus() {
        UUID request = insertRequest(NORTH, NORTH_INITIATOR, null, BigDecimal.ONE);

        assertThatThrownBy(() -> insertProblemWithOurSide("EXTERNAL_MISSING", request, true, null, "TRK-00000001",
                null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("problem_our_result_complete");
    }

    @Test
    void problemOurSideNeedsRecord() {
        UUID request = insertRequest(NORTH, NORTH_INITIATOR, null, BigDecimal.ONE);

        assertThatThrownBy(() -> insertProblemWithOurSide("EXTERNAL_MISSING", request, false, "REJECTED", null,
                null, "No"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("problem_our_side_needs_record");
    }

    @Test
    void problemOurSideMatchesKind() {
        UUID request = insertRequest(NORTH, NORTH_INITIATOR, null, BigDecimal.ONE);

        assertThatThrownBy(() -> insertProblemWithOurSide("DELIVERY_FAILED", request, true, null, null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("problem_our_side_matches_kind");
    }

    @Test
    void ourRecordedIsRequired() {
        UUID request = insertRequest(NORTH, NORTH_INITIATOR, null, BigDecimal.ONE);

        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO problem (id, kind, status, request_id, details, opened_at)
                        VALUES (:id, 'DELIVERY_FAILED', 'OPEN', :request, 'why', :now)""")
                .param("id", UUID.randomUUID())
                .param("request", request)
                .param("now", NOW)
                .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("our_recorded");
    }

    @Test
    void retryFromCannotExceedAttempts() {
        UUID request = insertRequest(NORTH, NORTH_INITIATOR, null, BigDecimal.ONE);
        insertOperation(request, UUID.randomUUID());

        assertThatThrownBy(() -> jdbc.sql("UPDATE dispatch_operation SET attempts = 2, retry_from = 3").update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("dispatch_operation_retry_from_range");
    }

    @Test
    void retryFromCannotBeNegative() {
        UUID request = insertRequest(NORTH, NORTH_INITIATOR, null, BigDecimal.ONE);
        insertOperation(request, UUID.randomUUID());

        assertThatThrownBy(() -> jdbc.sql("UPDATE dispatch_operation SET retry_from = -1").update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("dispatch_operation_retry_from_range");
    }

    @Test
    void appliedRecordMustExist() {
        UUID request = insertRequest(NORTH, NORTH_INITIATOR, null, BigDecimal.ONE);
        insertOperation(request, UUID.randomUUID());

        assertThatThrownBy(() -> jdbc.sql("UPDATE dispatch_operation SET applied_inbox_id = :id")
                .param("id", UUID.randomUUID())
                .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("dispatch_operation_applied_inbox_fk");
    }

    @Test
    void problemKindMustBeKnown() {
        UUID request = insertRequest(NORTH, NORTH_INITIATOR, null, BigDecimal.ONE);

        assertThatThrownBy(() -> insertProblem("LOST", "OPEN", request, null, null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("problem_kind_valid");
    }

    @Test
    void problemStatusMustBeKnown() {
        UUID request = insertRequest(NORTH, NORTH_INITIATOR, null, BigDecimal.ONE);

        assertThatThrownBy(() -> insertProblem("DELIVERY_UNKNOWN", "CLOSED", request, null, null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("problem_status_valid");
    }

    @Test
    void problemDecisionMustBeKnown() {
        UUID request = insertRequest(NORTH, NORTH_INITIATOR, null, BigDecimal.ONE);

        assertThatThrownBy(() -> insertProblem("DELIVERY_FAILED", "RESOLVED", request, null, "IGNORED",
                NORTH_OPERATOR, "x"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("problem_decision_valid");
    }

    @Test
    void openProblemHasNoResolution() {
        UUID request = insertRequest(NORTH, NORTH_INITIATOR, null, BigDecimal.ONE);

        assertThatThrownBy(() -> insertProblem("DELIVERY_UNKNOWN", "OPEN", request, null, "SYSTEM", null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("problem_resolution_complete");
    }

    @Test
    void resolvedProblemHasDecisionAndTime() {
        UUID request = insertRequest(NORTH, NORTH_INITIATOR, null, BigDecimal.ONE);

        assertThatThrownBy(() -> insertProblem("DELIVERY_UNKNOWN", "RESOLVED", request, null, null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("problem_resolution_complete");
    }

    @Test
    void systemDecisionHasNoAuthor() {
        UUID request = insertRequest(NORTH, NORTH_INITIATOR, null, BigDecimal.ONE);

        assertThatThrownBy(() -> insertProblem("DELIVERY_UNKNOWN", "RESOLVED", request, null, "SYSTEM",
                NORTH_OPERATOR, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("problem_system_has_no_author");
    }

    @Test
    void operatorDecisionHasAuthor() {
        UUID request = insertRequest(NORTH, NORTH_INITIATOR, null, BigDecimal.ONE);

        assertThatThrownBy(() -> insertProblem("DELIVERY_FAILED", "RESOLVED", request, null, "RETRIED", null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("problem_system_has_no_author");
    }

    @Test
    void conflictDecisionNeedsComment() {
        UUID request = insertRequest(NORTH, NORTH_INITIATOR, null, BigDecimal.ONE);
        UUID record = insertInboxRecord("CONFLICT");

        assertThatThrownBy(() -> insertProblem("RESULT_CONFLICT", "RESOLVED", request, record, "KEEP_OURS",
                NORTH_OPERATOR, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("problem_decision_needs_comment");
    }

    @Test
    void blankCommentIsRejected() {
        UUID record = insertInboxRecord("INVALID");

        assertThatThrownBy(() -> insertProblem("INVALID_MESSAGE", "RESOLVED", null, record, "ACKNOWLEDGED",
                NORTH_OPERATOR, "   "))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("problem_comment_not_blank");
    }

    @Test
    void deliveryProblemNeedsRequest() {
        assertThatThrownBy(() -> insertProblem("DELIVERY_UNKNOWN", "OPEN", null, null, null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("problem_source_matches_kind");
    }

    @Test
    void deliveryProblemHasNoRecord() {
        UUID request = insertRequest(NORTH, NORTH_INITIATOR, null, BigDecimal.ONE);
        UUID record = insertInboxRecord("INVALID");

        assertThatThrownBy(() -> insertProblem("DELIVERY_FAILED", "OPEN", request, record, null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("problem_source_matches_kind");
    }

    @Test
    void messageProblemNeedsRecord() {
        UUID request = insertRequest(NORTH, NORTH_INITIATOR, null, BigDecimal.ONE);

        assertThatThrownBy(() -> insertProblem("INVALID_MESSAGE", "OPEN", request, null, null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("problem_source_matches_kind");
    }

    @Test
    void resultConflictNeedsRequest() {
        UUID record = insertInboxRecord("CONFLICT");

        assertThatThrownBy(() -> insertProblem("RESULT_CONFLICT", "OPEN", null, record, null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("problem_source_matches_kind");
    }

    @Test
    void retriedIsOnlyForFailedDelivery() {
        UUID request = insertRequest(NORTH, NORTH_INITIATOR, null, BigDecimal.ONE);
        UUID record = insertInboxRecord("CONFLICT");

        assertThatThrownBy(() -> insertProblem("RESULT_CONFLICT", "RESOLVED", request, record, "RETRIED",
                NORTH_OPERATOR, "x"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("problem_decision_matches_kind");
    }

    @Test
    void conflictDecisionIsOnlyForConflicts() {
        UUID record = insertInboxRecord("INVALID");

        assertThatThrownBy(() -> insertProblem("INVALID_MESSAGE", "RESOLVED", null, record, "APPLY_CARRIER",
                NORTH_OPERATOR, "x"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("problem_decision_matches_kind");
    }

    @Test
    void systemDoesNotCloseMessageProblems() {
        UUID record = insertInboxRecord("INVALID");

        assertThatThrownBy(() -> insertProblem("INVALID_MESSAGE", "RESOLVED", null, record, "SYSTEM", null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("problem_decision_matches_kind");
    }

    @Test
    void secondOpenDeliveryProblemOfAKindIsRejected() {
        UUID request = insertRequest(NORTH, NORTH_INITIATOR, null, BigDecimal.ONE);
        insertProblem("DELIVERY_UNKNOWN", "OPEN", request, null, null, null, null);
        insertProblem("DELIVERY_FAILED", "OPEN", request, null, null, null, null);

        assertThatThrownBy(() -> insertProblem("DELIVERY_UNKNOWN", "OPEN", request, null, null, null, null))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("problem_open_per_request_kind");
    }

    @Test
    void deliveryProblemOpensAgainAfterTheLastOneIsResolved() {
        UUID request = insertRequest(NORTH, NORTH_INITIATOR, null, BigDecimal.ONE);
        insertProblem("DELIVERY_UNKNOWN", "RESOLVED", request, null, "SYSTEM", null, null);

        insertProblem("DELIVERY_UNKNOWN", "OPEN", request, null, null, null, null);

        assertThat(count("SELECT count(*) FROM problem WHERE kind = 'DELIVERY_UNKNOWN'")).isEqualTo(2);
    }

    @Test
    void secondProblemForARecordIsRejected() {
        UUID record = insertInboxRecord("INVALID");
        insertProblem("INVALID_MESSAGE", "RESOLVED", null, record, "ACKNOWLEDGED", NORTH_OPERATOR, "checked");

        assertThatThrownBy(() -> insertProblem("INVALID_MESSAGE", "OPEN", null, record, null, null, null))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("problem_per_inbox_record");
    }

    @Test
    void problemWithoutRequestIsAccepted() {
        insertProblem("INVALID_MESSAGE", "OPEN", null, insertInboxRecord("INVALID"), null, null, null);

        assertThat(count("SELECT count(*) FROM problem WHERE request_id IS NULL")).isEqualTo(1);
    }

    @Test
    void problemIndexesExist() {
        assertThat(jdbc.sql("SELECT indexdef FROM pg_indexes WHERE indexname = 'problem_list_idx'")
                .query(String.class).single()).contains("(status, opened_at DESC)");
        assertThat(jdbc.sql("SELECT indexdef FROM pg_indexes WHERE indexname = 'problem_request_idx'")
                .query(String.class).single()).contains("(request_id)");
    }

    @Test
    void inboxKeepsTheRawRecordAsText() {
        assertThat(jdbc.sql("""
                        SELECT data_type FROM information_schema.columns
                         WHERE table_name = 'inbox' AND column_name = 'payload'""")
                .query(String.class).single()).isEqualTo("text");
    }

    @Test
    void inboxAttemptsCannotBeNegative() {
        assertThatThrownBy(() -> insertInbox("APPLIED", 1L, -1, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("inbox_attempts_non_negative");
    }

    @Test
    void receivedInboxRecordNeedsANextAttempt() {
        assertThatThrownBy(() -> insertInbox("RECEIVED", 1L, 0, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("inbox_received_has_next_attempt");
    }

    @Test
    void processedInboxRecordHasNoNextAttempt() {
        assertThatThrownBy(() -> insertInbox("APPLIED", 1L, 0, NOW))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("inbox_received_has_next_attempt");
    }

    @Test
    void inboxStatusMustBeKnown() {
        assertThatThrownBy(() -> insertInbox("DONE", 1L, 0, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("inbox_processing_status_valid");
    }

    @Test
    void receivedAndFailedAreInboxStatuses() {
        insertInbox("FAILED", 2L, 5, null);
        insertInbox("RECEIVED", 3L, 0, NOW);

        assertThat(count("SELECT count(*) FROM inbox WHERE processing_status IN ('FAILED', 'RECEIVED')")).isEqualTo(2);
    }

    @Test
    void feedRecordNeedsAPosition() {
        assertThatThrownBy(() -> insertInbox("APPLIED", null, 0, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("inbox_feed_has_feed_seq");
    }

    @Test
    void feedPositionIsReceivedOnce() {
        insertInbox("APPLIED", 7L, 0, null);

        assertThatThrownBy(() -> insertInbox("APPLIED", 7L, 0, null))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("inbox_feed_seq_unique");
    }

    @Test
    void dueRecordsHaveAPartialIndex() {
        assertThat(jdbc.sql("SELECT indexdef FROM pg_indexes WHERE indexname = 'inbox_due_idx'")
                .query(String.class).single())
                .contains("(next_attempt_at)")
                .contains("WHERE (processing_status = 'RECEIVED'::text)");
    }

    @Test
    void openAttemptsHaveAPartialIndex() {
        assertThat(jdbc.sql("SELECT indexdef FROM pg_indexes WHERE indexname = 'exchange_attempt_open_idx'")
                .query(String.class).single())
                .contains("(started_at)")
                .contains("WHERE (finished_at IS NULL)");
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
    void trackingNumberLongerThan64IsRejected() {
        UUID id = insertRequest(NORTH, NORTH_INITIATOR, null, new BigDecimal("3.0"));

        assertThatThrownBy(() -> jdbc.sql("""
                        UPDATE delivery_request
                        SET business_status = 'SUCCEEDED', tracking_number = :tracking, price = 100, result_at = :now
                        WHERE id = :id""")
                .param("tracking", "T".repeat(65)).param("now", NOW).param("id", id).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("delivery_request_tracking_number_length");
    }

    @Test
    void rejectionReasonLongerThan1000IsRejected() {
        UUID id = insertRequest(NORTH, NORTH_INITIATOR, null, new BigDecimal("3.0"));

        assertThatThrownBy(() -> jdbc.sql("""
                        UPDATE delivery_request
                        SET business_status = 'REJECTED', rejection_reason = :reason, result_at = :now
                        WHERE id = :id""")
                .param("reason", "r".repeat(1001)).param("now", NOW).param("id", id).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("delivery_request_rejection_reason_length");
    }

    @Test
    void resultFieldsAtTheirLimitsAreAccepted() {
        UUID id = insertRequest(NORTH, NORTH_INITIATOR, null, new BigDecimal("3.0"));

        jdbc.sql("""
                        UPDATE delivery_request
                        SET business_status = 'SUCCEEDED', tracking_number = :tracking, price = 9999999999.99,
                            result_at = :now
                        WHERE id = :id""")
                .param("tracking", "T".repeat(64)).param("now", NOW).param("id", id).update();

        assertThat(count("SELECT count(*) FROM delivery_request WHERE char_length(tracking_number) = 64")).isEqualTo(1);
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
                        INSERT INTO inbox (id, message_id, feed_seq, payload, source, processing_status, received_at)
                        VALUES (:id, :message, 1, '{}', 'FEED', 'APPLIED', :now)
                        ON CONFLICT (source, message_id) WHERE message_id IS NOT NULL
                        DO UPDATE SET duplicate_count = inbox.duplicate_count + 1, last_duplicate_at = :now""")
                .param("id", UUID.randomUUID())
                .param("message", messageId)
                .param("now", NOW)
                .update();
    }

    private void insertInbox(String status, Long feedSeq, int attempts, OffsetDateTime nextAttemptAt) {
        jdbc.sql("""
                        INSERT INTO inbox (id, feed_seq, payload, source, processing_status, attempts, next_attempt_at,
                                           received_at)
                        VALUES (:id, :feedSeq, '{}', 'FEED', :status, :attempts, :next, :now)""")
                .param("id", UUID.randomUUID())
                .param("feedSeq", feedSeq)
                .param("status", status)
                .param("attempts", attempts)
                .param("next", nextAttemptAt)
                .param("now", NOW)
                .update();
    }

    private UUID insertInboxRecord(String status) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO inbox (id, payload, source, processing_status, received_at)
                        VALUES (:id, '{}', 'RECONCILIATION', :status, :now)""")
                .param("id", id)
                .param("status", status)
                .param("now", NOW)
                .update();
        return id;
    }

    private UUID insertProblem(String kind, String status, UUID requestId, UUID inboxId, String decision,
                               UUID resolvedBy, String comment) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO problem (id, kind, status, request_id, inbox_id, details, opened_at, resolved_at,
                                             resolved_by, decision, comment, our_recorded)
                        VALUES (:id, :kind, :status, :request, :inbox, 'why', :now, :resolvedAt, :resolvedBy,
                                :decision, :comment, false)""")
                .param("id", id)
                .param("kind", kind)
                .param("status", status)
                .param("request", requestId)
                .param("inbox", inboxId)
                .param("now", NOW)
                .param("resolvedAt", "RESOLVED".equals(status) ? NOW : null)
                .param("resolvedBy", resolvedBy)
                .param("decision", decision)
                .param("comment", comment)
                .update();
        return id;
    }

    private void insertProblemWithOurSide(String kind, UUID requestId, boolean recorded, String status,
                                          String tracking, BigDecimal price, String reason) {
        jdbc.sql("""
                        INSERT INTO problem (id, kind, status, request_id, details, opened_at, our_recorded,
                                             our_status, our_tracking_number, our_price, our_reason)
                        VALUES (:id, :kind, 'OPEN', :request, 'why', :now, :recorded, :ourStatus, :tracking, :price,
                                :reason)""")
                .param("id", UUID.randomUUID())
                .param("kind", kind)
                .param("request", requestId)
                .param("now", NOW)
                .param("recorded", recorded)
                .param("ourStatus", status)
                .param("tracking", tracking)
                .param("price", price)
                .param("reason", reason)
                .update();
    }

    private void insertEvent(UUID requestId, String kind, Long version, UUID actor, UUID inboxId, UUID problemId,
                             String changes) {
        jdbc.sql("""
                        INSERT INTO request_event (id, request_id, version, occurred_at, actor_id, kind, changes,
                                                   inbox_id, problem_id)
                        VALUES (:id, :request, :version, :now, :actor, :kind, CAST(:changes AS jsonb), :inbox,
                                :problem)""")
                .param("id", UUID.randomUUID())
                .param("request", requestId)
                .param("version", version)
                .param("now", NOW)
                .param("actor", actor)
                .param("kind", kind)
                .param("changes", changes)
                .param("inbox", inboxId)
                .param("problem", problemId)
                .update();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
