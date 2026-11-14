package com.acme.carrier.fault;

import static com.acme.carrier.support.Contract.matchesContract;
import static com.acme.carrier.support.Operations.body;
import static com.acme.carrier.support.Operations.submit;
import static com.acme.carrier.support.Operations.submitJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.acme.carrier.execution.Executor;
import com.acme.carrier.support.FakeSleeper;
import com.acme.carrier.support.IntegrationTest;
import com.acme.carrier.support.MutableClock;
import com.acme.carrier.support.TestRows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class UnavailableTests {

    private static final Faults UNAVAILABLE = new Faults(true, false, false, false);

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    FaultModes faults;
    @Autowired
    MutableClock clock;
    @Autowired
    FakeSleeper sleeper;
    @Autowired
    Executor executor;

    @AfterEach
    void cleanUp() {
        TestRows.resetState(faults, clock, sleeper);
        TestRows.deleteAll(jdbc);
    }

    @Test
    void submitIsRefusedAndNothingIsStored() throws Exception {
        faults.set(UNAVAILABLE);
        UUID id = UUID.randomUUID();

        expectUnavailable(mockMvc.perform(submit(body(id))));

        assertThat(operationCount(id)).isZero();
    }

    @Test
    void submitIsRefusedBeforeTheBodyIsValidated() throws Exception {
        faults.set(UNAVAILABLE);

        expectUnavailable(mockMvc.perform(submitJson("not json")));
    }

    @Test
    void operationStateIsRefused() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(submit(body(id))).andExpect(status().isAccepted());
        faults.set(UNAVAILABLE);

        expectUnavailable(mockMvc.perform(get("/operations/{id}", id)));
    }

    @Test
    void resultFeedIsRefused() throws Exception {
        faults.set(UNAVAILABLE);

        expectUnavailable(mockMvc.perform(get("/results").param("after", "0")));
    }

    @Test
    void refusalDoesNotChangeReceivedCountOfAnExistingOperation() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(submit(body(id))).andExpect(status().isAccepted());
        faults.set(UNAVAILABLE);

        expectUnavailable(mockMvc.perform(submit(body(id))));

        assertThat(jdbc.sql("SELECT received_count FROM operation WHERE operation_id = :id")
                .param("id", id).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void serviceEndpointsKeepWorking() throws Exception {
        faults.set(UNAVAILABLE);

        mockMvc.perform(get("/admin/faults"))
                .andExpect(status().isOk())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.unavailable").value(true));
        mockMvc.perform(get("/admin/operations"))
                .andExpect(status().isOk())
                .andExpect(matchesContract());
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void executorKeepsExecutingAndPublishingAcceptedOperations() throws Exception {
        long start = lastFeedSeq();
        UUID id = UUID.randomUUID();
        mockMvc.perform(submit(body(id))).andExpect(status().isAccepted());
        faults.set(UNAVAILABLE);

        assertThat(executor.runOnce()).isEqualTo(1);

        mockMvc.perform(get("/admin/operations").param("operationId", id.toString()))
                .andExpect(status().isOk())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.items[0].state").value("EXECUTED"))
                .andExpect(jsonPath("$.items[0].executionCount").value(1));
        assertThat(jdbc.sql("SELECT count(*) FROM feed_entry WHERE operation_id = :id AND feed_seq > :start")
                .param("id", id).param("start", start).query(Integer.class).single()).isEqualTo(2);
        expectUnavailable(mockMvc.perform(get("/results").param("after", String.valueOf(start))));

        faults.set(Faults.OFF);

        mockMvc.perform(get("/results").param("after", String.valueOf(start)))
                .andExpect(status().isOk())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[1].operationId").value(id.toString()))
                .andExpect(jsonPath("$.items[1].sequence").value(2));
    }

    @Test
    void endpointsWorkAgainAfterSwitchingOff() throws Exception {
        faults.set(UNAVAILABLE);
        UUID id = UUID.randomUUID();
        expectUnavailable(mockMvc.perform(submit(body(id))));

        faults.set(Faults.OFF);

        mockMvc.perform(submit(body(id)))
                .andExpect(status().isAccepted())
                .andExpect(matchesContract());
        mockMvc.perform(get("/operations/{id}", id))
                .andExpect(status().isOk())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.state").value("ACCEPTED"));
        mockMvc.perform(get("/results").param("after", "0"))
                .andExpect(status().isOk())
                .andExpect(matchesContract());
        assertThat(operationCount(id)).isEqualTo(1);
    }

    private static void expectUnavailable(ResultActions result) throws Exception {
        result.andExpect(status().isServiceUnavailable())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.code").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Carrier is temporarily unavailable"));
    }

    private int operationCount(UUID id) {
        return jdbc.sql("SELECT count(*) FROM operation WHERE operation_id = :id")
                .param("id", id).query(Integer.class).single();
    }

    private long lastFeedSeq() {
        return jdbc.sql("SELECT coalesce(max(feed_seq), 0) FROM feed_entry").query(Long.class).single();
    }
}
