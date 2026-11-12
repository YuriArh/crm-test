package com.acme.carrier.operation;

import static com.acme.carrier.support.Contract.matchesContract;
import static com.acme.carrier.support.Operations.body;
import static com.acme.carrier.support.Operations.submit;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.acme.carrier.execution.Executor;
import com.acme.carrier.support.IntegrationTest;
import com.acme.carrier.support.TestRows;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class OperationStateTests {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    Executor executor;
    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void cleanUp() {
        TestRows.deleteAll(jdbc);
    }

    @Test
    void acceptedOperationHasNoResultYet() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(submit(body(id))).andExpect(status().isAccepted());

        mockMvc.perform(get("/operations/{id}", id))
                .andExpect(status().isOk())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.operationId").value(id.toString()))
                .andExpect(jsonPath("$.state").value("ACCEPTED"))
                .andExpect(jsonPath("$.receivedAt").value("2026-11-12T09:00:00Z"))
                .andExpect(jsonPath("$.executedAt").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.result").value(Matchers.nullValue()));
    }

    @Test
    void executedOperationShowsItsLatestResultAsInTheFeed() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(submit(body(id))).andExpect(status().isAccepted());
        executor.runOnce();
        String finalMessageId = jdbc.sql("SELECT message_id::text FROM feed_entry WHERE operation_id = :id AND sequence = 2")
                .param("id", id).query(String.class).single();

        mockMvc.perform(get("/operations/{id}", id))
                .andExpect(status().isOk())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.state").value("EXECUTED"))
                .andExpect(jsonPath("$.executedAt").value("2026-11-12T09:00:00Z"))
                .andExpect(jsonPath("$.result.messageId").value(finalMessageId))
                .andExpect(jsonPath("$.result.sequence").value(2))
                .andExpect(jsonPath("$.result.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.result.trackingNumber").value(Matchers.startsWith("TRK-")))
                .andExpect(jsonPath("$.result.price").value(612.5));
    }

    @Test
    void unknownOperationIsNotFound() throws Exception {
        mockMvc.perform(get("/operations/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.code").value("OPERATION_NOT_FOUND"));
    }

    @Test
    void malformedIdIsRejected() throws Exception {
        mockMvc.perform(get("/operations/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void adminListShowsCountersAndFilters() throws Exception {
        UUID id = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        mockMvc.perform(submit(body(id))).andExpect(status().isAccepted());
        mockMvc.perform(submit(body(id))).andExpect(status().isAccepted());
        mockMvc.perform(submit(body(other))).andExpect(status().isAccepted());
        executor.runOnce();

        mockMvc.perform(get("/admin/operations").param("operationId", id.toString()))
                .andExpect(status().isOk())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].operationId").value(id.toString()))
                .andExpect(jsonPath("$.items[0].cargoDescription").value("Pallets"))
                .andExpect(jsonPath("$.items[0].weightKg").value(12.5))
                .andExpect(jsonPath("$.items[0].deadline").value("2026-12-01"))
                .andExpect(jsonPath("$.items[0].state").value("EXECUTED"))
                .andExpect(jsonPath("$.items[0].receivedCount").value(2))
                .andExpect(jsonPath("$.items[0].executionCount").value(1))
                .andExpect(jsonPath("$.items[0].result.status").value("SUCCEEDED"));

        mockMvc.perform(get("/admin/operations"))
                .andExpect(status().isOk())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.items.length()").value(2));

        mockMvc.perform(get("/admin/operations").param("operationId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }
}
