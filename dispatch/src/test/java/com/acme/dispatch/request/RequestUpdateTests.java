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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

import com.acme.dispatch.auth.AppUserRepository;
import com.acme.dispatch.config.IdGenerator;
import com.acme.dispatch.exchange.DispatchOperationDao;
import com.acme.dispatch.history.RequestEventDao;
import com.acme.dispatch.support.Contract;
import com.acme.dispatch.support.IntegrationTest;
import com.acme.dispatch.support.TestEvents;
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
    @Autowired
    RequestEventDao events;   // a spy, see IntegrationTest

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
    void initiatorSendingTheUnchangedAssigneeCanStillComment() throws Exception {
        // A client that always sends the whole form: assignee is empty and stays empty.
        patchAs(NORTH_INITIATOR_LOGIN, "{\"version\":0,\"comment\":\"Fragile\",\"assigneeId\":null}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comment").value("Fragile"));

        // Same with an assignee set by the operator.
        patchAs(NORTH_OPERATOR_LOGIN, "{\"version\":1,\"assigneeId\":\"" + NORTH_OPERATOR + "\"}")
                .andExpect(status().isOk());
        patchAs(NORTH_INITIATOR_LOGIN,
                "{\"version\":2,\"comment\":\"Very fragile\",\"assigneeId\":\"" + NORTH_OPERATOR + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comment").value("Very fragile"))
                .andExpect(jsonPath("$.assignee.id").value(NORTH_OPERATOR.toString()));
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

    @Test
    void commentChangeIsRecorded() throws Exception {
        clock.advance(Duration.ofMinutes(5));

        patchAs(NORTH_INITIATOR_LOGIN, "{\"version\":0,\"comment\":\"Fragile\"}").andExpect(status().isOk());

        assertThat(TestEvents.of(jdbc, requestId))
                .containsExactly("EDITED v1 by " + NORTH_INITIATOR + " [comment: null → Fragile]");
        assertThat(jdbc.sql("""
                        SELECT e.occurred_at = r.updated_at FROM request_event e
                          JOIN delivery_request r ON r.id = e.request_id WHERE e.request_id = :id""")
                .param("id", requestId).query(Boolean.class).single()).as("the time of the change").isTrue();
    }

    @Test
    void assigneeChangeIsRecorded() throws Exception {
        patchAs(NORTH_OPERATOR_LOGIN, "{\"version\":0,\"assigneeId\":\"" + NORTH_OPERATOR + "\"}")
                .andExpect(status().isOk());
        patchAs(NORTH_OPERATOR_LOGIN, "{\"version\":1,\"assigneeId\":null}").andExpect(status().isOk());

        assertThat(TestEvents.of(jdbc, requestId)).containsExactly(
                "EDITED v1 by " + NORTH_OPERATOR + " [assigneeId: null → " + NORTH_OPERATOR + "]",
                "EDITED v2 by " + NORTH_OPERATOR + " [assigneeId: " + NORTH_OPERATOR + " → null]");
    }

    @Test
    void bothFieldsInOneEvent() throws Exception {
        patchAs(NORTH_OPERATOR_LOGIN, "{\"version\":0,\"comment\":\"Mine\",\"assigneeId\":\"" + NORTH_OPERATOR
                + "\"}").andExpect(status().isOk());

        assertThat(TestEvents.of(jdbc, requestId)).containsExactly("EDITED v1 by " + NORTH_OPERATOR
                + " [comment: null → Mine, assigneeId: null → " + NORTH_OPERATOR + "]");
    }

    @Test
    void noopEditIsNotRecorded() throws Exception {
        patchAs(NORTH_INITIATOR_LOGIN, "{\"version\":0,\"comment\":null}").andExpect(status().isOk());

        assertThat(TestEvents.of(jdbc, requestId)).isEmpty();
    }

    @Test
    void forbiddenEditIsNotRecorded() throws Exception {
        patchAs(NORTH_INITIATOR_LOGIN, "{\"version\":0,\"assigneeId\":\"" + NORTH_OPERATOR + "\"}")
                .andExpect(status().isForbidden());

        assertThat(TestEvents.of(jdbc, requestId)).isEmpty();
    }

    @Test
    void staleEditIsNotRecorded() throws Exception {
        patchAs(NORTH_OPERATOR_LOGIN, "{\"version\":0,\"comment\":\"first\"}").andExpect(status().isOk());

        patchAs(NORTH_INITIATOR_LOGIN, "{\"version\":0,\"comment\":\"second\"}").andExpect(status().isConflict());

        assertThat(TestEvents.of(jdbc, requestId)).hasSize(1);
    }

    @Test
    void refusedEventRollsBackTheEdit() throws Exception {
        doThrow(new IllegalStateException("simulated: event refused")).when(events).insert(any());

        mvc.perform(patch("/api/requests/" + requestId)
                        .with(asUser(users, NORTH_INITIATOR_LOGIN))
                        .with(csrf())
                        .header(Contract.SKIP_HEADER, "an unexpected failure; 500 is not a documented answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"comment\":\"Fragile\"}"))
                .andExpect(status().isInternalServerError());

        assertThat(jdbc.sql("SELECT coalesce(comment, '-') || '/' || version FROM delivery_request WHERE id = :id")
                .param("id", requestId).query(String.class).single()).isEqualTo("-/0");
    }

    private ResultActions patchAs(String login, String body) throws Exception {
        return mvc.perform(patch("/api/requests/" + requestId)
                .with(asUser(users, login))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
