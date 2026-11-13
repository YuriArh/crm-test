package com.acme.dispatch;

import static com.acme.dispatch.support.DemoData.NORTH_INITIATOR_LOGIN;
import static com.acme.dispatch.support.TestSecurity.asUser;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.UUID;

import com.acme.dispatch.auth.AppUserRepository;
import com.acme.dispatch.support.IntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/** The contract check itself: an endpoint the contract does not describe fails the test that calls it. */
@IntegrationTest
class ContractCheckTests {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AppUserRepository users;

    @Test
    void pathMissingFromTheContractFails() {
        assertThatThrownBy(() -> mockMvc.perform(get("/api/requests/" + UUID.randomUUID() + "/history")
                .with(asUser(users, NORTH_INITIATOR_LOGIN))))
                .hasMessageContaining("validation.request.path.missing");
    }

    @Test
    void methodMissingFromTheContractFails() {
        assertThatThrownBy(() -> mockMvc.perform(delete("/api/requests/" + UUID.randomUUID())
                .with(asUser(users, NORTH_INITIATOR_LOGIN)).with(csrf())))
                .hasMessageContaining("validation.request.operation.notAllowed");
    }
}
