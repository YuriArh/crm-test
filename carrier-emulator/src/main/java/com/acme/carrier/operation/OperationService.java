package com.acme.carrier.operation;

import java.time.Clock;
import java.util.UUID;

import com.acme.carrier.fault.FaultModes;
import com.acme.carrier.web.CarrierException;
import com.acme.carrier.web.ErrorCode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationService {

    private final OperationDao operations;
    private final FaultModes faults;
    private final Clock clock;

    public OperationService(OperationDao operations, FaultModes faults, Clock clock) {
        this.operations = operations;
        this.faults = faults;
        this.clock = clock;
    }

    /**
     * Receives an operation. Idempotent by operationId: a repeat with the same content is only counted and never
     * executed again. A concurrent repeat waits on the primary key until the first insert commits.
     * A new operation remembers the {@code duplicate} and {@code delayed} fault modes switched on right now.
     */
    @Transactional
    public Receipt submit(SubmitOperationRequest request) {
        String hash = ContentHash.of(request);
        if (operations.insertIfAbsent(request, hash, faults.current().snapshot(), clock.instant())) {
            return Receipt.FIRST;
        }
        if (!operations.registerRepeat(request.operationId(), hash)) {
            throw new CarrierException(ErrorCode.PAYLOAD_MISMATCH,
                    "Operation " + request.operationId() + " was already submitted with different content");
        }
        return Receipt.REPEAT;
    }

    /** Whether a submit stored a new operation or counted a repeat of a known one. */
    public enum Receipt {
        FIRST,
        REPEAT
    }

    public OperationState state(UUID operationId) {
        return operations.find(operationId)
                .map(AdminOperation::toState)
                .orElseThrow(() -> new CarrierException(ErrorCode.OPERATION_NOT_FOUND,
                        "Operation " + operationId + " is not known"));
    }
}
