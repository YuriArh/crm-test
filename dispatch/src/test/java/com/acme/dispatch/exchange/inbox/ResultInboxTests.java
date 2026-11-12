package com.acme.dispatch.exchange.inbox;

import static com.acme.dispatch.support.TestClockConfiguration.START;
import static com.acme.dispatch.support.TestFeed.inProgress;
import static com.acme.dispatch.support.TestFeed.raw;
import static com.acme.dispatch.support.TestFeed.rejected;
import static com.acme.dispatch.support.TestFeed.succeeded;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import com.acme.dispatch.carrier.SubmitOutcome;
import com.acme.dispatch.carrier.SubmitResult;
import com.acme.dispatch.exchange.DeliveryStatus;
import com.acme.dispatch.exchange.DispatchOperation;
import com.acme.dispatch.exchange.DispatchOperationDao;
import com.acme.dispatch.exchange.SendWorker;
import com.acme.dispatch.request.RequestCard;
import com.acme.dispatch.support.FakeCarrier;
import com.acme.dispatch.support.IntegrationTest;
import com.acme.dispatch.support.MutableClock;
import com.acme.dispatch.support.TestRequests;
import com.acme.dispatch.support.TestRows;

import tools.jackson.databind.JsonNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class ResultInboxTests {

    @Autowired
    ResultInbox inbox;
    @Autowired
    TestRequests requests;
    @Autowired
    DispatchOperationDao operations;
    @Autowired
    SendWorker sender;
    @Autowired
    FakeCarrier carrier;
    @Autowired
    TransactionTemplate transaction;
    @Autowired
    MutableClock clock;
    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        clock.set(START);
        carrier.reset();
    }

    @AfterEach
    void cleanUp() {
        TestRows.deleteRequestData(jdbc);
    }

    @Test
    void inProgressThenSucceededUpdateTheRequest() {
        RequestCard request = requests.create("12.5");

        assertThat(accept(inProgress(1, request.operationId()))).isEqualTo(ResultInbox.Outcome.APPLIED);
        assertThat(row(request)).isEqualTo(new Row("IN_PROGRESS", null, null, null, null, 1));

        assertThat(accept(succeeded(2, request.operationId(), "TRK-1", "612.50"))).isEqualTo(ResultInbox.Outcome.APPLIED);
        assertThat(row(request)).isEqualTo(new Row("SUCCEEDED", "TRK-1", new BigDecimal("612.50"), null,
                Instant.parse("2026-11-12T09:00:05Z"), 2));
        assertThat(operation(request).lastAppliedSeq()).isEqualTo(2);
        assertThat(processing(request)).containsExactly("1:APPLIED", "2:APPLIED");
    }

    @Test
    void rejectionIsStoredWithItsReason() {
        RequestCard request = requests.create("15000");

        accept(rejected(1, request.operationId(), "Превышен допустимый вес"));

        Row row = row(request);
        assertThat(row.status()).isEqualTo("REJECTED");
        assertThat(row.reason()).isEqualTo("Превышен допустимый вес");
        assertThat(row.price()).isNull();
    }

    @Test
    void repeatedMessageIsCountedWithoutEffect() {
        RequestCard request = requests.create("1");
        JsonNode message = succeeded(1, request.operationId(), "TRK-1", "325.00");
        accept(message);

        assertThat(accept(message)).isEqualTo(ResultInbox.Outcome.DUPLICATE);

        assertThat(row(request).version()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*), max(duplicate_count) FROM inbox WHERE operation_id = :id")
                .param("id", request.operationId())
                .query((rs, n) -> rs.getInt(1) + "/" + rs.getInt(2)).single()).isEqualTo("1/1");
    }

    @Test
    void olderResultIsStaleAndDoesNotRollBack() {
        RequestCard request = requests.create("1");
        accept(succeeded(1, request.operationId(), "TRK-1", "325.00"));

        assertThat(accept(inProgress(2, request.operationId()))).isEqualTo(ResultInbox.Outcome.STALE);

        assertThat(row(request).status()).isEqualTo("SUCCEEDED");
        assertThat(row(request).version()).isEqualTo(1);
    }

    @Test
    void sameSequenceWithOtherContentIsAConflict() {
        RequestCard request = requests.create("1");
        accept(succeeded(1, request.operationId(), "TRK-1", "325.00"));

        assertThat(accept(rejected(2, request.operationId(), "Передумали"))).isEqualTo(ResultInbox.Outcome.CONFLICT);

        assertThat(row(request).status()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.sql("SELECT reason FROM inbox WHERE operation_id = :id AND processing_status = 'CONFLICT'")
                .param("id", request.operationId()).query(String.class).single())
                .contains("already applied with different content");
    }

    @Test
    void sameSequenceWithSameContentUnderANewMessageIdIsStale() {
        RequestCard request = requests.create("1");
        accept(succeeded(1, request.operationId(), "TRK-1", "325.00"));

        assertThat(accept(succeeded(2, request.operationId(), "TRK-1", "325.0"))).isEqualTo(ResultInbox.Outcome.STALE);
    }

    @Test
    void resultForAnUnknownOperationIsInvalid() {
        UUID unknown = UUID.randomUUID();

        assertThat(accept(inProgress(1, unknown))).isEqualTo(ResultInbox.Outcome.INVALID);

        assertThat(jdbc.sql("SELECT processing_status || ':' || reason FROM inbox WHERE operation_id = :id")
                .param("id", unknown).query(String.class).single())
                .isEqualTo("INVALID:Unknown operationId " + unknown);
    }

    @Test
    void malformedEntryIsKeptForReview() {
        assertThat(accept(raw("{\"feedSeq\": 9, \"messageId\": \"bad-1\", \"status\": \"DONE\"}")))
                .isEqualTo(ResultInbox.Outcome.INVALID);
        assertThat(accept(raw("{\"feedSeq\": 9, \"messageId\": \"bad-1\", \"status\": \"DONE\"}")))
                .as("repeat of the same broken message").isEqualTo(ResultInbox.Outcome.DUPLICATE);

        assertThat(jdbc.sql("SELECT processing_status || ':' || (payload->>'status') FROM inbox WHERE message_id = 'bad-1'")
                .query(String.class).single()).isEqualTo("INVALID:DONE");
    }

    @Test
    void resultConfirmsDeliveryAndCancelsTheRetry() {
        RequestCard request = requests.create("1");
        carrier.answer(new SubmitResult(SubmitOutcome.UNKNOWN, null, "No response in time"));
        sender.runOnce();
        assertThat(operation(request).deliveryStatus()).isEqualTo(DeliveryStatus.UNKNOWN);

        accept(inProgress(1, request.operationId()));

        DispatchOperation operation = operation(request);
        assertThat(operation.deliveryStatus()).isEqualTo(DeliveryStatus.ACKNOWLEDGED);
        assertThat(operation.acknowledgedVia()).isEqualTo("RESULT");
        assertThat(operation.lastError()).isNull();
        clock.advance(java.time.Duration.ofMinutes(10));
        assertThat(sender.runOnce()).as("no resend after the result").isZero();
    }

    @Test
    void resultArrivingDuringASendWinsOverTheSendOutcome() {
        RequestCard request = requests.create("1");
        carrier.answer(new SubmitResult(SubmitOutcome.TEMPORARY_ERROR, 503, "HTTP 503"));
        carrier.duringSubmit(() -> transaction.executeWithoutResult(status ->
                inbox.accept(inProgress(1, request.operationId()), InboxSource.FEED)));

        sender.runOnce();

        DispatchOperation operation = operation(request);
        assertThat(operation.deliveryStatus()).isEqualTo(DeliveryStatus.ACKNOWLEDGED);
        assertThat(operation.acknowledgedVia()).isEqualTo("RESULT");
        assertThat(jdbc.sql("SELECT outcome FROM exchange_attempt WHERE operation_id = :id")
                .param("id", request.operationId()).query(String.class).single())
                .as("the attempt is still recorded").isEqualTo("TEMPORARY_ERROR");
    }

    private ResultInbox.Outcome accept(JsonNode entry) {
        return transaction.execute(status -> inbox.accept(entry, InboxSource.FEED));
    }

    private DispatchOperation operation(RequestCard request) {
        return operations.findByRequestId(request.id()).orElseThrow();
    }

    private java.util.List<String> processing(RequestCard request) {
        return jdbc.sql("SELECT sequence || ':' || processing_status FROM inbox WHERE operation_id = :id ORDER BY sequence")
                .param("id", request.operationId()).query(String.class).list();
    }

    private Row row(RequestCard request) {
        return jdbc.sql("""
                        SELECT business_status, tracking_number, price, rejection_reason, result_at, version
                          FROM delivery_request WHERE id = :id""")
                .param("id", request.id())
                .query((rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getString(4),
                        rs.getObject(5, java.time.OffsetDateTime.class) == null ? null
                                : rs.getObject(5, java.time.OffsetDateTime.class).withOffsetSameInstant(ZoneOffset.UTC).toInstant(),
                        rs.getLong(6)))
                .single();
    }

    record Row(String status, String tracking, BigDecimal price, String reason, Instant resultAt, long version) {
    }
}
