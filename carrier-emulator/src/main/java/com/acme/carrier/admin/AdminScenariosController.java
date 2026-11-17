package com.acme.carrier.admin;

import java.util.UUID;

import jakarta.validation.Valid;

import com.acme.carrier.config.UuidV7Generator;
import com.acme.carrier.operation.AdminOperation;
import com.acme.carrier.operation.OperationDao;
import com.acme.carrier.web.CarrierException;
import com.acme.carrier.web.ErrorCode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo scenarios the honest carrier never produces ({@code docs/specs/carrier-scenarios.md}): it changes the result of
 * an operation, or loses an operation. They change the carrier's state through its own code, as test helpers of a
 * sandbox do, so that reconciliation in {@code dispatch} meets a conflict or a lost operation. Not used by
 * {@code dispatch}.
 */
@RestController
public class AdminScenariosController {

    private static final Logger log = LoggerFactory.getLogger(AdminScenariosController.class);

    private final ScenarioDao scenarios;
    private final OperationDao operations;
    private final UuidV7Generator ids;

    public AdminScenariosController(ScenarioDao scenarios, OperationDao operations, UuidV7Generator ids) {
        this.scenarios = scenarios;
        this.operations = operations;
        this.ids = ids;
    }

    /** The carrier changes its result: a new message with the same sequence; only {@code GET /operations} shows it. */
    @PutMapping("/admin/operations/{operationId}/result")
    AdminOperation changeResult(@PathVariable UUID operationId, @Valid @RequestBody AdminResultChange change) {
        AdminOperation before = known(operationId);
        if (!scenarios.changeResult(operationId, change, ids.newId())) {
            throw new CarrierException(ErrorCode.OPERATION_NOT_SETTLED,
                    "Operation " + operationId + " has no settled result to change");
        }
        AdminOperation after = known(operationId);
        log.warn("Scenario changeResult: operation {} result changed (was {}, now {})",
                operationId, before.result(), after.result());
        return after;
    }

    /** The carrier loses the operation: it and its feed entries are gone. */
    @DeleteMapping("/admin/operations/{operationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void forget(@PathVariable UUID operationId) {
        int entries = scenarios.forget(operationId).orElseThrow(() -> notFound(operationId));
        log.warn("Scenario forget: operation {} forgotten with {} feed entries", operationId, entries);
    }

    private AdminOperation known(UUID operationId) {
        return operations.find(operationId).orElseThrow(() -> notFound(operationId));
    }

    private static CarrierException notFound(UUID operationId) {
        return new CarrierException(ErrorCode.OPERATION_NOT_FOUND, "Operation " + operationId + " is not known");
    }
}
