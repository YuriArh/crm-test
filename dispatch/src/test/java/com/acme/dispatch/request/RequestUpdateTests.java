package com.acme.dispatch.request;

import static com.acme.dispatch.support.DemoData.NORTH;
import static com.acme.dispatch.support.DemoData.NORTH_INITIATOR;
import static com.acme.dispatch.support.DemoData.NORTH_INITIATOR_LOGIN;
import static com.acme.dispatch.support.DemoData.NORTH_OPERATOR;
import static com.acme.dispatch.support.DemoData.NORTH_OPERATOR_LOGIN;
import static com.acme.dispatch.support.DemoData.SOUTH_INITIATOR;
import static com.acme.dispatch.support.DemoData.SOUTH_INITIATOR_LOGIN;
import static com.acme.dispatch.support.TestSecurity.asUser;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import com.acme.dispatch.auth.AppUserRepository;
import com.acme.dispatch.config.IdGenerator;
import com.acme.dispatch.exchange.DispatchOperationDao;
import com.acme.dispatch.support.IntegrationTest;
import com.acme.dispatch.support.MutableClock;
import com.acme.dispatch.support.TestClockConfiguration;
import com.acme.dispatch.support.TestRows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class RequestUpdateTests {

    @Autowired
    MockMvc mvc;
    @Autowired
    AppUserRepository users;
    @Autowired
    DeliveryRequestRepository requests;
    @Autowired
    DispatchOperationDao operations;
    @Autowired
    IdGenerator ids;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    MutableClock clock;

    UUID requestId;

    @BeforeEach
    void createRequest() {
        clock.set(TestClockConfiguration.START);
        DeliveryRequest request = DeliveryRequest.create(ids.newId(), requests.nextNumber(), NORTH, NORTH_INITIATOR,
                new DeliveryContent("Pallets", new BigDecimal("12.5"), "Main st 1", LocalDate.of(2026, 11, 20)),
                TestClockConfiguration.START);
        requestId = requests.save(request).getId();
        operations.insertPending(requestId, ids.newId(), "0".repeat(64), TestClockConfiguration.START);
    }

    @AfterEach
    void cleanUp() {
        TestRows.deleteRequestData(jdbc);
    }

    @Test
    void ownerChangesTheComment() throws Exception {
        patchAs(NORTH_INITIATOR_LOGIN, "{\"version\":0,\"comment\":\"Fragile\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comment").value("Fragile"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void operatorOfTheServiceChangesTheComment() throws Exception {
        patchAs(NORTH_OPERATOR_LOGIN, "{\"version\":0,\"comment\":\"Checked\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comment").value("Checked"));
    }

    @Test
    void someoneWhoCannotSeeTheRequestGets404() throws Exception {
        patchAs(SOUTH_INITIATOR_LOGIN, "{\"version\":0,\"comment\":\"x\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void operatorAssignsHimself() throws Exception {
        patchAs(NORTH_OPERATOR_LOGIN, "{\"version\":0,\"assigneeId\":\"" + NORTH_OPERATOR + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignee.id").value(NORTH_OPERATOR.toString()))
                .andExpect(jsonPath("$.assignee.displayName").value("Оператор (Север)"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void initiatorCannotAssign() throws Exception {
        patchAs(NORTH_INITIATOR_LOGIN, "{\"version\":0,\"assigneeId\":\"" + NORTH_OPERATOR + "\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void assigneeMustBeAnOperatorOfTheSameService() throws Exception {
        for (UUID candidate : new UUID[] {NORTH_INITIATOR, SOUTH_INITIATOR, UUID.randomUUID()}) {
            patchAs(NORTH_OPERATOR_LOGIN, "{\"version\":0,\"assigneeId\":\"" + candidate + "\"}")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("INVALID_ASSIGNEE"));
        }
    }

    @Test
    void staleVersionIsAConflictWithTheCurrentState() throws Exception {
        patchAs(NORTH_OPERATOR_LOGIN, "{\"version\":0,\"comment\":\"first\"}").andExpect(status().isOk());

        patchAs(NORTH_INITIATOR_LOGIN, "{\"version\":0,\"comment\":\"second\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.current.version").value(1))
                .andExpect(jsonPath("$.current.comment").value("first"));
    }

    @Test
    void repeatingTheSameChangeIsANoOpEvenWithAStaleVersion() throws Exception {
        patchAs(NORTH_INITIATOR_LOGIN, "{\"version\":0,\"comment\":\"Fragile\"}").andExpect(status().isOk());

        patchAs(NORTH_INITIATOR_LOGIN, "{\"version\":0,\"comment\":\"Fragile\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void explicitNullClearsTheAssigneeAndOmittedFieldsAreKept() throws Exception {
        patchAs(NORTH_OPERATOR_LOGIN, "{\"version\":0,\"comment\":\"keep me\"}").andExpect(status().isOk());
        patchAs(NORTH_OPERATOR_LOGIN, "{\"version\":1,\"assigneeId\":\"" + NORTH_OPERATOR + "\"}")
                .andExpect(jsonPath("$.comment").value("keep me"));

        patchAs(NORTH_OPERATOR_LOGIN, "{\"version\":2,\"assigneeId\":null}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignee").isEmpty())
                .andExpect(jsonPath("$.comment").value("keep me"))
                .andExpect(jsonPath("$.version").value(3));
    }

    @Test
    void contentCannotBeChanged() throws Exception {
        patchAs(NORTH_INITIATOR_LOGIN, "{\"version\":0,\"deliveryAddress\":\"Elsewhere\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("deliveryAddress"));
    }

    @Test
    void malformedIdIsNotFound() throws Exception {
        mvc.perform(patch("/api/requests/not-a-uuid").with(asUser(users, NORTH_INITIATOR_LOGIN)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"comment\":\"x\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void csrfTokenIsRequired() throws Exception {
        mvc.perform(patch("/api/requests/" + requestId).with(asUser(users, NORTH_INITIATOR_LOGIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"comment\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    private ResultActions patchAs(String login, String body) throws Exception {
        return mvc.perform(patch("/api/requests/" + requestId)
                .with(asUser(users, login))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
