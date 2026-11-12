package com.acme.carrier.operation;

import static com.acme.carrier.support.Contract.matchesContract;
import static com.acme.carrier.support.Operations.body;
import static com.acme.carrier.support.Operations.submit;
import static com.acme.carrier.support.Operations.submitJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.acme.carrier.support.IntegrationTest;
import com.acme.carrier.support.TestRows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class SubmitTests {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    OperationService service;
    @Autowired
    OperationDao operations;
    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void cleanUp() {
        TestRows.deleteAll(jdbc);
    }

    @Test
    void firstSubmitIsAccepted() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(submit(body(id)))
                .andExpect(status().isAccepted())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.operationId").value(id.toString()))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        AdminOperation stored = operations.find(id).orElseThrow();
        assertThat(stored.state()).isEqualTo(OperationStateValue.ACCEPTED);
        assertThat(stored.receivedCount()).isEqualTo(1);
        assertThat(stored.executionCount()).isZero();
        assertThat(stored.cargoDescription()).isEqualTo("Pallets");
        assertThat(stored.weightKg()).isEqualByComparingTo("12.5");
    }

    @Test
    void repeatWithSameContentIsAcceptedAndCounted() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(submit(body(id))).andExpect(status().isAccepted());

        Map<String, Object> repeat = body(id);
        repeat.put("weightKg", "12.50");
        repeat.put("cargoDescription", "  Pallets ");
        mockMvc.perform(submit(repeat))
                .andExpect(status().isAccepted())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        AdminOperation stored = operations.find(id).orElseThrow();
        assertThat(stored.receivedCount()).isEqualTo(2);
        assertThat(stored.executionCount()).isZero();
    }

    @Test
    void sameIdWithDifferentContentIsAConflict() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(submit(body(id))).andExpect(status().isAccepted());

        Map<String, Object> other = body(id);
        other.put("weightKg", 13);
        mockMvc.perform(submit(other))
                .andExpect(status().isConflict())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.code").value("PAYLOAD_MISMATCH"));

        AdminOperation stored = operations.find(id).orElseThrow();
        assertThat(stored.receivedCount()).isEqualTo(1);
        assertThat(stored.weightKg()).isEqualByComparingTo("12.5");
    }

    @Test
    void invalidFieldsAreRejected() throws Exception {
        Map<String, Object> invalid = body(UUID.randomUUID());
        invalid.put("weightKg", 1.25);
        invalid.put("cargoDescription", " ");

        mockMvc.perform(submit(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value(
                        "cargoDescription: must not be blank; weightKg: must have at most one decimal place"));
        assertThat(count()).isZero();
    }

    @Test
    void missingFieldIsRejected() throws Exception {
        Map<String, Object> invalid = body(UUID.randomUUID());
        invalid.remove("deadline");

        mockMvc.perform(submit(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.message").value("deadline: must not be null"));
    }

    @Test
    void unknownFieldIsRejected() throws Exception {
        Map<String, Object> invalid = body(UUID.randomUUID());
        invalid.put("priority", "HIGH");

        mockMvc.perform(submit(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(count()).isZero();
    }

    @Test
    void malformedJsonIsRejected() throws Exception {
        mockMvc.perform(submitJson("{\"operationId\":"))
                .andExpect(status().isBadRequest())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void concurrentRepeatsStoreOneOperation() throws Exception {
        UUID id = UUID.randomUUID();
        SubmitOperationRequest request = new SubmitOperationRequest(id, "Pallets", new BigDecimal("12.5"),
                "Main st 1", LocalDate.of(2026, 12, 1));
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Void>> calls = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                calls.add(() -> {
                    service.submit(request);
                    return null;
                });
            }
            for (Future<Void> result : pool.invokeAll(calls)) {
                result.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(count()).isEqualTo(1);
        assertThat(operations.find(id).orElseThrow().receivedCount()).isEqualTo(threads);
    }

    private int count() {
        return jdbc.sql("SELECT count(*) FROM operation").query(Integer.class).single();
    }
}
