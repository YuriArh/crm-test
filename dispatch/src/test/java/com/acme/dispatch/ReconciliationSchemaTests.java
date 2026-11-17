package com.acme.dispatch;

import static com.acme.dispatch.support.DemoData.NORTH;
import static com.acme.dispatch.support.DemoData.NORTH_INITIATOR;
import static com.acme.dispatch.support.DemoData.NORTH_OPERATOR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.acme.dispatch.support.IntegrationTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * The database enforces the integrity of reconciliation (V11, {@code docs/specs/reconciliation.md} §2.9): each rule
 * is its own named constraint, and each test breaks exactly one of them.
 */
@IntegrationTest
@Transactional
class ReconciliationSchemaTests {

    static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 12, 9, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    JdbcClient jdbc;

    UUID request;
    UUID operation;

    @BeforeEach
    void createRequest() {
        request = UUID.randomUUID();
        operation = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO delivery_request (id, service_id, owner_id, cargo_description, weight_kg,
                            delivery_address, deadline, created_at, updated_at, version)
                        VALUES (:id, :service, :owner, 'Pallets', 1, 'Main st 1', :deadline, :now, :now, 0)""")
                .param("id", request)
                .param("service", NORTH)
                .param("owner", NORTH_INITIATOR)
                .param("deadline", LocalDate.of(2026, 11, 20))
                .param("now", NOW)
                .update();
        jdbc.sql("""
                        INSERT INTO dispatch_operation (request_id, operation_id, payload_hash, next_attempt_at,
                            created_at, updated_at)
                        VALUES (:request, :operation, :hash, :now, :now, :now)""")
                .param("request", request)
                .param("operation", operation)
                .param("hash", "0".repeat(64))
                .param("now", NOW)
                .update();
    }

    @Test
    void validRowIsAccepted() {
        insertReconciliation(Map.of());

        assertThat(jdbc.sql("SELECT count(*) FROM reconciliation").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void runTypeMustBeKnown() {
        assertRejected(Map.of("run_type", "NIGHTLY"), "reconciliation_run_type_valid");
    }

    @Test
    void outcomeMustBeKnown() {
        assertRejected(Map.of("outcome", "MAYBE"), "reconciliation_outcome_valid");
    }

    @Test
    void carrierStateMustBeKnown() {
        assertRejected(Map.of("carrier_state", "LOST"), "reconciliation_carrier_state_valid");
    }

    @Test
    void carrierResultStatusMustBeKnown() {
        assertRejected(Map.of("carrier_state", "EXECUTED", "carrier_result_status", "IN_PROGRESS"),
                "reconciliation_carrier_result_status_valid");
    }

    @Test
    void deliveryStatusMustBeKnown() {
        assertRejected(Map.of("delivery_status", "DONE"), "reconciliation_delivery_status_valid");
    }

    @Test
    void manualRunHasAuthor() {
        assertRejected(Map.of("run_type", "MANUAL"), "reconciliation_manual_has_author");
    }

    @Test
    void backgroundRunHasNoAuthor() {
        assertRejected(Map.of("performed_by", NORTH_OPERATOR), "reconciliation_manual_has_author");
    }

    @Test
    void manualRunWithAuthorIsAccepted() {
        insertReconciliation(Map.of("run_type", "MANUAL", "performed_by", NORTH_OPERATOR));
    }

    @Test
    void errorHasNoCarrierState() {
        assertRejected(Map.of("outcome", "ERROR"), "reconciliation_error_without_state");
    }

    @Test
    void answerHasCarrierState() {
        assertRejected(nulls("carrier_state"), "reconciliation_error_without_state");
    }

    @Test
    void externalMissingMeansNotFound() {
        assertRejected(Map.of("outcome", "EXTERNAL_MISSING"), "reconciliation_missing_is_not_found");
    }

    @Test
    void conflictNeedsCarrierAnswer() {
        assertRejected(Map.of("outcome", "CONFLICT", "carrier_state", "NOT_FOUND"),
                "reconciliation_conflict_has_answer");
    }

    @Test
    void conflictWithoutCarrierResultIsAccepted() {
        Map<String, Object> row = ours("SUCCEEDED", "TRK-1", new BigDecimal("10.00"), null);
        row.put("outcome", "CONFLICT");
        insertReconciliation(row);
    }

    @Test
    void externalAheadNeedsResultAndRecord() {
        assertRejected(withResult(Map.of("outcome", "EXTERNAL_AHEAD")), "reconciliation_ahead_has_result");
    }

    @Test
    void carrierResultOnlyWhenExecuted() {
        Map<String, Object> row = withResult(Map.of());
        row.put("carrier_state", "ACCEPTED");
        assertRejected(row, "reconciliation_result_needs_executed");
    }

    @Test
    void succeededNeedsTrackingAndPrice() {
        Map<String, Object> row = withResult(Map.of());
        row.put("carrier_tracking_number", null);
        assertRejected(row, "reconciliation_result_complete");
    }

    @Test
    void rejectedNeedsReason() {
        Map<String, Object> row = withResult(Map.of());
        row.put("carrier_result_status", "REJECTED");
        row.put("carrier_tracking_number", null);
        row.put("carrier_price", null);
        assertRejected(row, "reconciliation_result_complete");
    }

    @Test
    void resultFieldsNeedAStatus() {
        assertRejected(Map.of("carrier_state", "EXECUTED", "carrier_sequence", 2), "reconciliation_result_complete");
    }

    @Test
    void acknowledgedNeedsCarrierAnswer() {
        assertRejected(Map.of("acknowledged", true, "carrier_state", "NOT_FOUND", "outcome", "EXTERNAL_MISSING"),
                "reconciliation_acknowledged_has_answer");
    }

    @Test
    void detailsAreRequired() {
        assertRejected(nulls("details"), "details");
    }

    @Test
    void localResultIsOptional() {
        insertReconciliation(nulls("local_result"));
    }

    @Test
    void reconciliationNeedsKnownOperation() {
        assertRejected(Map.of("operation_id", UUID.randomUUID()), "reconciliation_operation_fk");
    }

    @Test
    void reconcileTimesTogether() {
        assertThatThrownBy(() -> jdbc.sql("UPDATE dispatch_operation SET reconciled_at = :now")
                .param("now", NOW).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("dispatch_operation_reconcile_times_together");
    }

    @Test
    void nextReconcileNotBeforeLast() {
        assertThatThrownBy(() -> jdbc.sql("""
                        UPDATE dispatch_operation SET reconciled_at = :now, next_reconcile_at = :earlier""")
                .param("now", NOW).param("earlier", NOW.minusMinutes(1)).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("dispatch_operation_reconcile_order");
    }

    @Test
    void problemKindAcceptsReconciliationKinds() {
        UUID reconciliation = insertReconciliation(Map.of("outcome", "CONFLICT"));
        insertProblem("EXTERNAL_MISSING", "OPEN", null, null, null, null);
        insertProblem("RECONCILIATION_CONFLICT", "OPEN", reconciliation, null, null, null);

        assertThat(jdbc.sql("SELECT count(*) FROM problem").query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void externalMissingHasNoReconciliation() {
        UUID reconciliation = insertReconciliation(Map.of());
        assertThatThrownBy(() -> insertProblem("EXTERNAL_MISSING", "OPEN", reconciliation, null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("problem_source_matches_kind");
    }

    @Test
    void reconciliationConflictNeedsReconciliation() {
        assertThatThrownBy(() -> insertProblem("RECONCILIATION_CONFLICT", "OPEN", null, null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("problem_source_matches_kind");
    }

    @Test
    void oldKindsHaveNoReconciliation() {
        UUID reconciliation = insertReconciliation(Map.of());
        assertThatThrownBy(() -> insertProblem("DELIVERY_FAILED", "OPEN", reconciliation, null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("problem_source_matches_kind");
    }

    @Test
    void externalMissingAcceptsItsDecisions() {
        insertProblem("EXTERNAL_MISSING", "RESOLVED", null, "SYSTEM", null, null);
        insertProblem("EXTERNAL_MISSING", "RESOLVED", null, "RETRIED", NORTH_OPERATOR, null);
        insertProblem("EXTERNAL_MISSING", "RESOLVED", null, "KEEP_OURS", NORTH_OPERATOR, "carrier lost it");
    }

    @Test
    void externalMissingRejectsApplyCarrier() {
        assertThatThrownBy(() -> insertProblem("EXTERNAL_MISSING", "RESOLVED", null, "APPLY_CARRIER",
                NORTH_OPERATOR, "x"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("problem_decision_matches_kind");
    }

    @Test
    void reconciliationConflictAcceptsItsDecisions() {
        UUID reconciliation = insertReconciliation(Map.of("outcome", "CONFLICT"));
        insertProblem("RECONCILIATION_CONFLICT", "RESOLVED", reconciliation, "SYSTEM", null, null);
        insertProblem("RECONCILIATION_CONFLICT", "RESOLVED", reconciliation, "APPLY_CARRIER", NORTH_OPERATOR, "x");
        insertProblem("RECONCILIATION_CONFLICT", "RESOLVED", reconciliation, "KEEP_OURS", NORTH_OPERATOR, "x");
    }

    @Test
    void reconciliationConflictRejectsRetried() {
        UUID reconciliation = insertReconciliation(Map.of("outcome", "CONFLICT"));
        assertThatThrownBy(() -> insertProblem("RECONCILIATION_CONFLICT", "RESOLVED", reconciliation, "RETRIED",
                NORTH_OPERATOR, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("problem_decision_matches_kind");
    }

    @Test
    void reconciliationConflictRejectsAcknowledged() {
        UUID reconciliation = insertReconciliation(Map.of("outcome", "CONFLICT"));
        assertThatThrownBy(() -> insertProblem("RECONCILIATION_CONFLICT", "RESOLVED", reconciliation,
                "ACKNOWLEDGED", NORTH_OPERATOR, "x"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("problem_decision_matches_kind");
    }

    @Test
    void secondOpenReconciliationProblemOfAKindIsRejected() {
        insertProblem("EXTERNAL_MISSING", "OPEN", null, null, null, null);

        assertThatThrownBy(() -> insertProblem("EXTERNAL_MISSING", "OPEN", null, null, null, null))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("problem_open_per_request_kind");
    }

    @Test
    void sameMessageIdFromTwoSourcesIsAccepted() {
        insertInbox("FEED", "msg-1", 1L);
        insertInbox("RECONCILIATION", "msg-1", null);

        assertThat(jdbc.sql("SELECT count(*) FROM inbox WHERE message_id = 'msg-1'").query(Long.class).single())
                .isEqualTo(2);
    }

    @Test
    void sameMessageIdFromOneSourceIsRejected() {
        insertInbox("RECONCILIATION", "msg-1", null);

        assertThatThrownBy(() -> insertInbox("RECONCILIATION", "msg-1", null))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("inbox_source_message_id_unique");
    }

    @Test
    void localSideIsAccepted() {
        insertReconciliation(ours("SUCCEEDED", "TRK-00000001", new BigDecimal("612.50"), null));
        insertReconciliation(ours("REJECTED", null, null, "Address is not served"));

        assertThat(jdbc.sql("SELECT count(*) FROM reconciliation WHERE local_status IS NOT NULL")
                .query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void localStatusMustBeKnown() {
        assertRejected(ours("DONE", null, null, null), "reconciliation_local_status_valid");
    }

    @Test
    void localSucceededNeedsTrackingNumber() {
        assertRejected(ours("SUCCEEDED", null, new BigDecimal("1"), null), "reconciliation_local_result_complete");
    }

    @Test
    void localSucceededNeedsPrice() {
        assertRejected(ours("SUCCEEDED", "TRK-00000001", null, null), "reconciliation_local_result_complete");
    }

    @Test
    void localRejectedNeedsReason() {
        assertRejected(ours("REJECTED", null, null, null), "reconciliation_local_result_complete");
    }

    @Test
    void localFieldsNeedStatus() {
        Map<String, Object> row = ours(null, "TRK-00000001", null, null);
        row.put("local_result", null);
        assertRejected(row, "reconciliation_local_result_complete");
    }

    @Test
    void localFieldsNeedKey() {
        Map<String, Object> withoutKey = ours("REJECTED", null, null, "No");
        withoutKey.put("local_result", null);
        assertRejected(withoutKey, "reconciliation_local_key_matches");
    }

    @Test
    void localKeyNeedsFields() {
        assertRejected(Map.of("local_result", "REJECTED|||No"), "reconciliation_local_key_matches");
    }

    @Test
    void reconciliationIndexesExist() {
        assertThat(jdbc.sql("SELECT indexdef FROM pg_indexes WHERE indexname = 'reconciliation_request_idx'")
                .query(String.class).single()).contains("(request_id, checked_at DESC)");
        assertThat(jdbc.sql("SELECT indexdef FROM pg_indexes WHERE indexname = 'dispatch_operation_next_reconcile_idx'")
                .query(String.class).single()).contains("(next_reconcile_at)");
    }

    private void assertRejected(Map<String, Object> overrides, String constraint) {
        assertThatThrownBy(() -> insertReconciliation(overrides))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    /** A complete SUCCEEDED result of the carrier on top of the overrides. */
    private static Map<String, Object> withResult(Map<String, Object> overrides) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("carrier_state", "EXECUTED");
        row.put("carrier_result_status", "SUCCEEDED");
        row.put("carrier_tracking_number", "TRK-00000001");
        row.put("carrier_price", new BigDecimal("612.50"));
        row.put("carrier_sequence", 2);
        row.put("carrier_message_id", "msg-2");
        row.put("carrier_occurred_at", NOW);
        row.putAll(overrides);
        return row;
    }

    /** Our result at the check: the structured fields and the comparison key built from them. */
    private static Map<String, Object> ours(String status, String tracking, BigDecimal price, String reason) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("local_result", status + "|" + (tracking == null ? "" : tracking) + "|"
                + (price == null ? "" : price.stripTrailingZeros().toPlainString()) + "|"
                + (reason == null ? "" : reason));
        row.put("local_status", status);
        row.put("local_tracking_number", tracking);
        row.put("local_price", price);
        row.put("local_reason", reason);
        return row;
    }

    private static Map<String, Object> nulls(String column) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(column, null);
        return row;
    }

    /** A valid background MATCH row (carrier ACCEPTED, no results anywhere), changed by {@code overrides}. */
    private UUID insertReconciliation(Map<String, Object> overrides) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", UUID.randomUUID());
        row.put("request_id", request);
        row.put("operation_id", operation);
        row.put("checked_at", NOW);
        row.put("run_type", "SCHEDULED");
        row.put("performed_by", null);
        row.put("outcome", "MATCH");
        row.put("acknowledged", false);
        row.put("delivery_status", "ACKNOWLEDGED");
        row.put("local_result", null);
        row.put("carrier_state", "ACCEPTED");
        row.put("carrier_result_status", null);
        row.put("carrier_tracking_number", null);
        row.put("carrier_price", null);
        row.put("carrier_reason", null);
        row.put("carrier_sequence", null);
        row.put("carrier_message_id", null);
        row.put("carrier_occurred_at", null);
        row.put("carrier_response", "{}");
        row.put("details", "ok");
        row.put("inbox_id", null);
        row.putAll(overrides);
        String columns = String.join(", ", row.keySet());
        String values = ":" + String.join(", :", row.keySet());
        JdbcClient.StatementSpec sql = jdbc.sql("INSERT INTO reconciliation (" + columns + ") VALUES (" + values + ")");
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            sql = sql.param(entry.getKey(), entry.getValue());
        }
        sql.update();
        return (UUID) row.get("id");
    }

    private void insertProblem(String kind, String status, UUID reconciliationId, String decision, UUID resolvedBy,
                               String comment) {
        jdbc.sql("""
                        INSERT INTO problem (id, kind, status, request_id, reconciliation_id, details, opened_at,
                                             resolved_at, resolved_by, decision, comment, our_recorded)
                        VALUES (:id, :kind, :status, :request, :reconciliation, 'why', :now, :resolvedAt,
                                :resolvedBy, :decision, :comment, false)""")
                .param("id", UUID.randomUUID())
                .param("kind", kind)
                .param("status", status)
                .param("request", request)
                .param("reconciliation", reconciliationId)
                .param("now", NOW)
                .param("resolvedAt", "RESOLVED".equals(status) ? NOW : null)
                .param("resolvedBy", resolvedBy)
                .param("decision", decision)
                .param("comment", comment)
                .update();
    }

    private void insertInbox(String source, String messageId, Long feedSeq) {
        jdbc.sql("""
                        INSERT INTO inbox (id, message_id, feed_seq, payload, source, processing_status, received_at)
                        VALUES (:id, :message, :feedSeq, '{}', :source, 'APPLIED', :now)""")
                .param("id", UUID.randomUUID())
                .param("message", messageId)
                .param("feedSeq", feedSeq)
                .param("source", source)
                .param("now", NOW)
                .update();
    }
}
