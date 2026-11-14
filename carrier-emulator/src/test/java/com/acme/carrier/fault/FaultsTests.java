package com.acme.carrier.fault;

import static com.acme.carrier.support.Contract.matchesContract;
import static com.acme.carrier.support.Operations.body;
import static com.acme.carrier.support.Operations.submit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.acme.carrier.support.FakeSleeper;
import com.acme.carrier.support.IntegrationTest;
import com.acme.carrier.support.MutableClock;
import com.acme.carrier.support.TestRows;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class FaultsTests {

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

    @AfterEach
    void cleanUp() {
        TestRows.resetState(faults, clock, sleeper);
        TestRows.deleteAll(jdbc);
    }

    @Test
    void allModesAreOffByDefault() throws Exception {
        mockMvc.perform(get("/admin/faults"))
                .andExpect(status().isOk())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.unavailable").value(false))
                .andExpect(jsonPath("$.loseResponse").value(false))
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.delayed").value(false));
    }

    @Test
    void modesAreSwitchedAndReadBack() throws Exception {
        mockMvc.perform(put("/admin/faults").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unavailable\":false,\"loseResponse\":true,\"duplicate\":true,\"delayed\":false}"))
                .andExpect(status().isOk())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.loseResponse").value(true))
                .andExpect(jsonPath("$.duplicate").value(true));

        mockMvc.perform(get("/admin/faults"))
                .andExpect(status().isOk())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.unavailable").value(false))
                .andExpect(jsonPath("$.loseResponse").value(true))
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.delayed").value(false));
    }

    @Test
    void incompleteBodyIsRejected() throws Exception {
        mockMvc.perform(put("/admin/faults").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unavailable\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(faults.current()).isEqualTo(Faults.OFF);
    }

    @Test
    void newOperationRemembersDuplicateAndDelayed() throws Exception {
        faults.set(new Faults(false, false, true, true));
        UUID id = UUID.randomUUID();
        mockMvc.perform(submit(body(id))).andExpect(status().isAccepted());
        faults.set(Faults.OFF);

        mockMvc.perform(get("/admin/operations").param("operationId", id.toString()))
                .andExpect(status().isOk())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.items[0].faults", Matchers.contains("DUPLICATE", "DELAYED")))
                .andExpect(jsonPath("$.items[0].resultPublishAt").value(Matchers.nullValue()));
    }

    @Test
    void repeatDoesNotChangeTheRememberedModes() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(submit(body(id))).andExpect(status().isAccepted());
        faults.set(new Faults(false, false, true, true));
        mockMvc.perform(submit(body(id))).andExpect(status().isAccepted());

        mockMvc.perform(get("/admin/operations").param("operationId", id.toString()))
                .andExpect(status().isOk())
                .andExpect(matchesContract())
                .andExpect(jsonPath("$.items[0].faults", Matchers.empty()));
    }
}
