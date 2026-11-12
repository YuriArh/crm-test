package com.acme.dispatch;

import static com.acme.dispatch.support.DemoData.NORTH;
import static com.acme.dispatch.support.DemoData.NORTH_INITIATOR;
import static com.acme.dispatch.support.DemoData.NORTH_OPERATOR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.acme.dispatch.auth.AppUser;
import com.acme.dispatch.auth.AppUserRepository;
import com.acme.dispatch.auth.Role;
import com.acme.dispatch.auth.ServiceUnitRepository;
import com.acme.dispatch.config.IdGenerator;
import com.acme.dispatch.exchange.DeliveryStatus;
import com.acme.dispatch.exchange.DispatchOperation;
import com.acme.dispatch.exchange.DispatchOperationDao;
import com.acme.dispatch.request.BusinessStatus;
import com.acme.dispatch.request.DeliveryContent;
import com.acme.dispatch.request.DeliveryRequest;
import com.acme.dispatch.request.DeliveryRequestRepository;
import com.acme.dispatch.support.IntegrationTest;
import com.acme.dispatch.support.TestClockConfiguration;
import com.acme.dispatch.support.TestRows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Entities and DAOs map to the real schema both ways. */
@IntegrationTest
class RepositoryMappingTests {

    static final Instant NOW = TestClockConfiguration.START;
    static final DeliveryContent CONTENT =
            new DeliveryContent("Pallets", new BigDecimal("12.5"), "Main st 1", LocalDate.of(2026, 11, 20));

    @Autowired
    DeliveryRequestRepository requests;
    @Autowired
    DispatchOperationDao operations;
    @Autowired
    AppUserRepository users;
    @Autowired
    ServiceUnitRepository services;
    @Autowired
    IdGenerator ids;
    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void cleanUp() {
        TestRows.deleteRequestData(jdbc);
    }

    @Test
    void deliveryRequestRoundTrip() {
        DeliveryRequest saved = requests.save(newRequest());

        DeliveryRequest loaded = requests.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getVersion()).isZero();
        assertThat(loaded.getNumber()).isEqualTo(saved.getNumber());
        assertThat(loaded.getServiceId()).isEqualTo(NORTH);
        assertThat(loaded.getOwnerId()).isEqualTo(NORTH_INITIATOR);
        assertThat(loaded.content()).isEqualTo(CONTENT);
        assertThat(loaded.getBusinessStatus()).isEqualTo(BusinessStatus.AWAITING);
        assertThat(loaded.getCreatedAt()).isEqualTo(NOW);
        assertThat(loaded.getAssigneeId()).isNull();
    }

    @Test
    void savingAChangeBumpsTheVersion() {
        DeliveryRequest saved = requests.save(newRequest());

        DeliveryRequest loaded = requests.findById(saved.getId()).orElseThrow();
        loaded.changeComment("Fragile", NOW.plusSeconds(60));
        loaded.changeAssignee(NORTH_OPERATOR, NOW.plusSeconds(60));
        requests.save(loaded);

        DeliveryRequest reloaded = requests.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getVersion()).isEqualTo(1);
        assertThat(reloaded.getComment()).isEqualTo("Fragile");
        assertThat(reloaded.getAssigneeId()).isEqualTo(NORTH_OPERATOR);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void savingAStaleCopyFails() {
        UUID id = requests.save(newRequest()).getId();
        DeliveryRequest first = requests.findById(id).orElseThrow();
        DeliveryRequest second = requests.findById(id).orElseThrow();

        first.changeComment("first", NOW);
        requests.save(first);
        second.changeComment("second", NOW);

        assertThatThrownBy(() -> requests.save(second)).isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    void unchangedValuesReportNoChange() {
        DeliveryRequest request = newRequest();

        assertThat(request.changeComment(null, NOW)).isFalse();
        assertThat(request.changeAssignee(null, NOW)).isFalse();
        assertThat(request.changeComment("x", NOW)).isTrue();
        assertThat(request.changeComment("x", NOW)).isFalse();
    }

    @Test
    void numbersComeFromTheSequenceInOrder() {
        long first = requests.nextNumber();
        long second = requests.nextNumber();

        assertThat(second).isGreaterThan(first);
    }

    @Test
    void operationIsInsertedPendingAndReadBack() {
        UUID requestId = requests.save(newRequest()).getId();
        UUID operationId = ids.newId();

        operations.insertPending(requestId, operationId, "a".repeat(64), NOW);

        DispatchOperation operation = operations.findByRequestId(requestId).orElseThrow();
        assertThat(operation.operationId()).isEqualTo(operationId);
        assertThat(operation.deliveryStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(operation.attempts()).isZero();
        assertThat(operation.nextAttemptAt()).isEqualTo(NOW);
        assertThat(operation.lockedUntil()).isNull();
    }

    @Test
    void usersAreFoundCaseInsensitively() {
        AppUser operator = users.findByUsernameIgnoreCase("NORTH.Operator").orElseThrow();

        assertThat(operator.getId()).isEqualTo(NORTH_OPERATOR);
        assertThat(operator.getRole()).isEqualTo(Role.OPERATOR);
        assertThat(operator.isEnabled()).isTrue();
    }

    @Test
    void enabledOperatorsOfAServiceAreListed() {
        assertThat(users.findEnabledOperators(NORTH)).extracting(AppUser::getId).containsExactly(NORTH_OPERATOR);
    }

    @Test
    void servicesAreReadable() {
        assertThat(services.findById(NORTH).orElseThrow().getCode()).isEqualTo("NORTH");
    }

    private DeliveryRequest newRequest() {
        return DeliveryRequest.create(ids.newId(), requests.nextNumber(), NORTH, NORTH_INITIATOR, CONTENT, NOW);
    }
}
