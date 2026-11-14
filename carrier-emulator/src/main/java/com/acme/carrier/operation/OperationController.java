package com.acme.carrier.operation;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import jakarta.validation.Valid;

import com.acme.carrier.fault.FaultModes;
import com.acme.carrier.fault.LostResponse;
import com.acme.carrier.operation.OperationService.Receipt;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OperationController {

    private final OperationService service;
    private final FaultModes faults;
    private final LostResponse lostResponse;
    private final Clock clock;

    public OperationController(OperationService service, FaultModes faults, LostResponse lostResponse, Clock clock) {
        this.service = service;
        this.faults = faults;
        this.lostResponse = lostResponse;
        this.clock = clock;
    }

    /**
     * Receipt only: {@code 202} for the first submit and for every repeat with the same content.
     * With {@code loseResponse} on, the response to the first submit is held after the receipt has committed.
     */
    @PostMapping("/operations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    SubmitOperationResponse submit(@Valid @RequestBody SubmitOperationRequest request) {
        Instant startedAt = clock.instant();
        if (service.submit(request) == Receipt.FIRST && faults.current().loseResponse()) {
            lostResponse.hold(request.operationId(), startedAt);
        }
        return new SubmitOperationResponse(request.operationId(), "ACCEPTED");
    }

    @GetMapping("/operations/{operationId}")
    OperationState get(@PathVariable UUID operationId) {
        return service.state(operationId);
    }

    record SubmitOperationResponse(UUID operationId, String status) {
    }
}
