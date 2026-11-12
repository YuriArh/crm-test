package com.acme.carrier.admin;

import java.util.List;
import java.util.UUID;

import com.acme.carrier.operation.AdminOperation;
import com.acme.carrier.operation.OperationDao;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Lets a check see the carrier's actual state and execution counters independently of dispatch. */
@RestController
public class AdminOperationsController {

    static final int LIMIT = 100;

    private final OperationDao operations;

    public AdminOperationsController(OperationDao operations) {
        this.operations = operations;
    }

    @GetMapping("/admin/operations")
    Items list(@RequestParam(required = false) UUID operationId) {
        if (operationId != null) {
            return new Items(operations.find(operationId).stream().toList());
        }
        return new Items(operations.listNewest(LIMIT));
    }

    record Items(List<AdminOperation> items) {
    }
}
