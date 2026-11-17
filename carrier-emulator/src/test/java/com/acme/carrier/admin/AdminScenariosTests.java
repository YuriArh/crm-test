package com.acme.carrier.admin;

import static com.acme.carrier.support.Contract.matchesContract;
import static com.acme.carrier.support.Operations.body;
import static com.acme.carrier.support.Operations.submit;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.acme.carrier.execution.Executor;
import com.acme.carrier.support.IntegrationTest;
import com.acme.carrier.support.TestRows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Demo scenarios of the carrier ({@code docs/specs/carrier-scenarios.md}): kept minimal on purpose — the main path
 * of each endpoint and the refusal of an unsettled result.
 */
@IntegrationTest
class AdminScenariosTests {

    private static final String NEW_RESULT =
            "{\"status\":\"SUCCEEDED\",\"trackingNumber\":\"TRK-FFFFFFFF\",\"price\":999.00,\"reason\":null}";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    Executor executor;

    @AfterEach
    void cleanUp() {
        TestRows.deleteAll(jdbc);
    }

    @Test
    void changesTheResultOfAnExecutedOperation() throws Exception {
        UUID id = submitted();
        executor.runOnce();
        String messageId = jdbc.sql("SELECT result_message_id::text FROM operation WHERE operation_id = :id")
                .param("id", id).query(String.class).single();

        mockMvc.perform(changeResult(id))
                .andExpect(status().isOk())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.result.trackingNumber").value("TRK-FFFFFFFF"));

        mockMvc.perform(get("/operations/{id}", id))
                .andExpect(status().isOk())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.result.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.result.trackingNumber").value("TRK-FFFFFFFF"))
                .andExpect(jsonPath("$.result.price").value(999.0))
                .andExpect(jsonPath("$.result.messageId").value(not(messageId)));
    }

    @Test
    void operationNotExecutedYetIsNotSettled() throws Exception {
        UUID id = submitted();

        mockMvc.perform(changeResult(id))
                .andExpect(status().isConflict())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.code").value("OPERATION_NOT_SETTLED"));
    }

    @Test
    void forgetsAnExecutedOperation() throws Exception {
        UUID id = submitted();
        executor.runOnce();

        mockMvc.perform(delete("/admin/operations/{id}", id))
                .andExpect(status().isNoContent())
                .andExpect(matchesContract());

        mockMvc.perform(get("/operations/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(matchesContract());
    }

    private UUID submitted() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(submit(body(id))).andExpect(status().isAccepted());
        return id;
    }

    private static MockHttpServletRequestBuilder changeResult(UUID id) {
        return put("/admin/operations/{id}/result", id).contentType(MediaType.APPLICATION_JSON).content(NEW_RESULT);
    }
}
