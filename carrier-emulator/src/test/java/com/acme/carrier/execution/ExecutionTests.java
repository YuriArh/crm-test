package com.acme.carrier.execution;

import static com.acme.carrier.support.Operations.body;
import static com.acme.carrier.support.Operations.submit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.acme.carrier.operation.AdminOperation;
import com.acme.carrier.operation.OperationDao;
import com.acme.carrier.operation.OperationStateValue;
import com.acme.carrier.operation.ResultStatus;
import com.acme.carrier.support.IntegrationTest;
import com.acme.carrier.support.TestClockConfiguration;
import com.acme.carrier.support.TestRows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class ExecutionTests {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    Executor executor;
    @Autowired
    OperationDao operations;
    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void cleanUp() {
        TestRows.deleteAll(jdbc);
    }

    @Test
    void acceptedOperationIsExecutedAndPublished() throws Exception {
        UUID id = submitted(body(UUID.randomUUID()));

        assertThat(executor.runOnce()).isEqualTo(1);

        AdminOperation executed = operations.find(id).orElseThrow();
        assertThat(executed.state()).isEqualTo(OperationStateValue.EXECUTED);
        assertThat(executed.executionCount()).isEqualTo(1);
        assertThat(executed.executedAt()).isEqualTo(TestClockConfiguration.NOW);
        assertThat(feed(id)).extracting(Entry::sequence, Entry::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, "IN_PROGRESS"),
                        org.assertj.core.groups.Tuple.tuple(2, "SUCCEEDED"));
        Entry result = feed(id).get(1);
        assertThat(result.trackingNumber()).isEqualTo(OutcomePolicy.trackingNumber(id));
        assertThat(result.price()).isEqualTo("612.50");
        assertThat(executed.result().status()).isEqualTo(ResultStatus.SUCCEEDED);
    }

    @Test
    void outcomeFollowsThePolicy() throws Exception {
        Map<String, Object> heavy = body(UUID.randomUUID());
        heavy.put("weightKg", 15000);
        Map<String, Object> badAddress = body(UUID.randomUUID());
        badAddress.put("deliveryAddress", "Тест-отказ, 1");
        UUID heavyId = submitted(heavy);
        UUID badAddressId = submitted(badAddress);

        executor.runOnce();

        assertThat(feed(heavyId).get(1)).extracting(Entry::status, Entry::reason, Entry::trackingNumber, Entry::price)
                .containsExactly("REJECTED", "Превышен допустимый вес", null, null);
        assertThat(feed(badAddressId).get(1).reason()).isEqualTo("Адрес не обслуживается");
    }

    @Test
    void operationIsExecutedOnlyOnceDespiteRepeatsAndPasses() throws Exception {
        Map<String, Object> body = body(UUID.randomUUID());
        UUID id = submitted(body);
        executor.runOnce();

        mockMvc.perform(submit(body)).andExpect(status().isAccepted());
        mockMvc.perform(submit(body)).andExpect(status().isAccepted());
        assertThat(executor.runOnce()).isZero();
        assertThat(executor.runOnce()).isZero();

        AdminOperation operation = operations.find(id).orElseThrow();
        assertThat(operation.executionCount()).isEqualTo(1);
        assertThat(operation.receivedCount()).isEqualTo(3);
        assertThat(feed(id)).hasSize(2);
    }

    @Test
    void nothingToExecuteIsANoOp() {
        assertThat(executor.runOnce()).isZero();
    }

    @Test
    void batchExecutesSeveralOperationsInReceiptOrder() throws Exception {
        UUID first = submitted(body(UUID.randomUUID()));
        UUID second = submitted(body(UUID.randomUUID()));

        assertThat(executor.runOnce()).isEqualTo(2);

        List<UUID> order = jdbc.sql("SELECT operation_id FROM feed_entry ORDER BY feed_seq")
                .query(UUID.class).list();
        assertThat(order).hasSize(4);
        assertThat(order).allSatisfy(id -> assertThat(id).isIn(first, second));
        // Entries of one operation are adjacent: IN_PROGRESS, then its result.
        assertThat(order.get(0)).isEqualTo(order.get(1));
        assertThat(order.get(2)).isEqualTo(order.get(3));
    }

    private UUID submitted(Map<String, Object> body) throws Exception {
        mockMvc.perform(submit(body)).andExpect(status().isAccepted());
        return UUID.fromString((String) body.get("operationId"));
    }

    private List<Entry> feed(UUID operationId) {
        return jdbc.sql("""
                        SELECT sequence, status, tracking_number, price::text AS price, reason
                          FROM feed_entry WHERE operation_id = :id ORDER BY feed_seq
                        """)
                .param("id", operationId)
                .query((rs, row) -> new Entry(rs.getInt("sequence"), rs.getString("status"),
                        rs.getString("tracking_number"), rs.getString("price"), rs.getString("reason")))
                .list();
    }

    record Entry(int sequence, String status, String trackingNumber, String price, String reason) {
    }
}
