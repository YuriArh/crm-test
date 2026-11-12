package com.acme.carrier.operation;

import java.util.UUID;

import jakarta.validation.Valid;

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

    public OperationController(OperationService service) {
        this.service = service;
    }

    /** Receipt only: {@code 202} for the first submit and for every repeat with the same content. */
    @PostMapping("/operations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    SubmitOperationResponse submit(@Valid @RequestBody SubmitOperationRequest request) {
        service.submit(request);
        return new SubmitOperationResponse(request.operationId(), "ACCEPTED");
    }

    @GetMapping("/operations/{operationId}")
    OperationState get(@PathVariable UUID operationId) {
        return service.state(operationId);
    }

    record SubmitOperationResponse(UUID operationId, String status) {
    }
}
