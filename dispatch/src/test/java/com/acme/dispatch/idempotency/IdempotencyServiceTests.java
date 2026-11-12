package com.acme.dispatch.idempotency;

import static com.acme.dispatch.support.DemoData.NORTH_INITIATOR;
import static com.acme.dispatch.support.DemoData.SOUTH_INITIATOR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.acme.dispatch.common.web.ApiException;
import com.acme.dispatch.common.web.ErrorCode;
import com.acme.dispatch.support.IntegrationTest;
import com.acme.dispatch.support.TestRows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class IdempotencyServiceTests {

    static final String OPERATION = "CREATE_REQUEST";
    static final String KEY = "key-1";

    record CreateCommand(String cargo, int weight) {
    }

    record CreatedDto(UUID id, String number, List<String> tags) {
    }

    @Autowired
    IdempotencyService idempotency;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    JdbcClient jdbc;

    final AtomicInteger actionRuns = new AtomicInteger();

    @AfterEach
    void cleanUp() {
        TestRows.deleteRequestData(jdbc);
    }

    @Test
    void firstCallRunsActionAndStoresResponse() {
        UUID resourceId = UUID.randomUUID();

        IdempotentOutcome<CreatedDto> outcome = call(NORTH_INITIATOR, KEY, OPERATION,
                new CreateCommand("Pallets", 10), countingAction(resourceId));

        assertThat(actionRuns).hasValue(1);
        assertThat(outcome.replayed()).isFalse();
        assertThat(outcome.status()).isEqualTo(201);
        assertThat(outcome.resourceId()).isEqualTo(resourceId);
        assertThat(outcome.body()).isEqualTo(dto(resourceId));

        Map<String, Object> row = jdbc.sql("""
                        SELECT operation, request_hash, response_status, response_body ->> 'number' AS number, resource_id
                        FROM idempotency_key WHERE user_id = :userId AND idem_key = :key""")
                .param("userId", NORTH_INITIATOR)
                .param("key", KEY)
                .query()
                .singleRow();
        assertThat(row)
                .containsEntry("operation", OPERATION)
                .containsEntry("request_hash", IdempotencyService.requestHash(new CreateCommand("Pallets", 10)))
                .containsEntry("response_status", 201)
                .containsEntry("number", "D-1")
                .containsEntry("resource_id", resourceId);
    }

    @Test
    void repeatWithSameKeyAndRequestReplaysStoredResponse() {
        UUID resourceId = UUID.randomUUID();
        call(NORTH_INITIATOR, KEY, OPERATION, new CreateCommand("Pallets", 10), countingAction(resourceId));

        IdempotentOutcome<CreatedDto> replay = call(NORTH_INITIATOR, KEY, OPERATION,
                new CreateCommand("Pallets", 10), countingAction(UUID.randomUUID()));

        assertThat(actionRuns).hasValue(1);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.resourceId()).isEqualTo(resourceId);
        assertThat(replay.body()).isEqualTo(dto(resourceId));
    }

    @Test
    void sameKeyWithDifferentRequestIsRejected() {
        call(NORTH_INITIATOR, KEY, OPERATION, new CreateCommand("Pallets", 10), countingAction(UUID.randomUUID()));

        assertThatThrownBy(() -> call(NORTH_INITIATOR, KEY, OPERATION,
                new CreateCommand("Pallets", 11), countingAction(UUID.randomUUID())))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
        assertThat(actionRuns).hasValue(1);
        assertThat(ErrorCode.IDEMPOTENCY_KEY_REUSED.status().value()).isEqualTo(422);
    }

    @Test
    void sameKeyForDifferentOperationIsRejected() {
        call(NORTH_INITIATOR, KEY, OPERATION, new CreateCommand("Pallets", 10), countingAction(UUID.randomUUID()));

        assertThatThrownBy(() -> call(NORTH_INITIATOR, KEY, "CANCEL_REQUEST",
                new CreateCommand("Pallets", 10), countingAction(UUID.randomUUID())))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
        assertThat(actionRuns).hasValue(1);
    }

    @Test
    void keysAreIndependentPerUser() {
        UUID northId = UUID.randomUUID();
        UUID southId = UUID.randomUUID();
        call(NORTH_INITIATOR, KEY, OPERATION, new CreateCommand("Pallets", 10), countingAction(northId));

        IdempotentOutcome<CreatedDto> south = call(SOUTH_INITIATOR, KEY, OPERATION,
                new CreateCommand("Pallets", 10), countingAction(southId));

        assertThat(actionRuns).hasValue(2);
        assertThat(south.replayed()).isFalse();
        assertThat(south.resourceId()).isEqualTo(southId);
        assertThat(jdbc.sql("SELECT count(*) FROM idempotency_key").query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void failedActionRollsBackKeySoRetryRunsAgain() {
        assertThatThrownBy(() -> call(NORTH_INITIATOR, KEY, OPERATION, new CreateCommand("Pallets", 10), () -> {
            actionRuns.incrementAndGet();
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class).hasMessage("boom");
        assertThat(jdbc.sql("SELECT count(*) FROM idempotency_key").query(Integer.class).single()).isZero();

        UUID resourceId = UUID.randomUUID();
        IdempotentOutcome<CreatedDto> retry = call(NORTH_INITIATOR, KEY, OPERATION,
                new CreateCommand("Pallets", 10), countingAction(resourceId));

        assertThat(actionRuns).hasValue(2);
        assertThat(retry.replayed()).isFalse();
        assertThat(retry.resourceId()).isEqualTo(resourceId);
    }

    @Test
    void blankOrTooLongKeyIsRejected() {
        for (String key : new String[] {"", "   ", null, "k".repeat(101)}) {
            assertThatThrownBy(() -> call(NORTH_INITIATOR, key, OPERATION,
                    new CreateCommand("Pallets", 10), countingAction(UUID.randomUUID())))
                    .as("key %s", key)
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
        assertThat(actionRuns).hasValue(0);

        call(NORTH_INITIATOR, "k".repeat(100), OPERATION, new CreateCommand("Pallets", 10),
                countingAction(UUID.randomUUID()));
        assertThat(actionRuns).hasValue(1);
    }

    @Test
    void requiresSurroundingTransaction() {
        assertThatThrownBy(() -> idempotency.execute(NORTH_INITIATOR, KEY, OPERATION,
                new CreateCommand("Pallets", 10), CreatedDto.class, countingAction(UUID.randomUUID())))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(actionRuns).hasValue(0);
    }

    @Test
    void parallelDuplicateWaitsAndReplays() throws Exception {
        CountDownLatch start = new CountDownLatch(2);
        AtomicInteger counter = new AtomicInteger();
        Supplier<IdempotentResponse<CreatedDto>> slowAction = () -> {
            sleep(300);
            counter.incrementAndGet();
            UUID id = UUID.randomUUID();
            return new IdempotentResponse<>(201, dto(id), id);
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<IdempotentOutcome<CreatedDto>>> futures = List.of(
                    pool.submit(() -> {
                        start.countDown();
                        start.await();
                        return call(NORTH_INITIATOR, KEY, OPERATION, new CreateCommand("Pallets", 10), slowAction);
                    }),
                    pool.submit(() -> {
                        start.countDown();
                        start.await();
                        return call(NORTH_INITIATOR, KEY, OPERATION, new CreateCommand("Pallets", 10), slowAction);
                    }));
            IdempotentOutcome<CreatedDto> first = futures.get(0).get(10, TimeUnit.SECONDS);
            IdempotentOutcome<CreatedDto> second = futures.get(1).get(10, TimeUnit.SECONDS);

            assertThat(counter).hasValue(1);
            assertThat(first.resourceId()).isEqualTo(second.resourceId());
            assertThat(first.body()).isEqualTo(second.body());
            assertThat(List.of(first.replayed(), second.replayed())).containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
        }
    }

    private IdempotentOutcome<CreatedDto> call(UUID userId, String key, String operation, Object request,
                                               Supplier<IdempotentResponse<CreatedDto>> action) {
        return tx.execute(status -> idempotency.execute(userId, key, operation, request, CreatedDto.class, action));
    }

    private Supplier<IdempotentResponse<CreatedDto>> countingAction(UUID resourceId) {
        return () -> {
            actionRuns.incrementAndGet();
            return new IdempotentResponse<>(201, dto(resourceId), resourceId);
        };
    }

    private static CreatedDto dto(UUID id) {
        return new CreatedDto(id, "D-1", List.of("a", "b"));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
