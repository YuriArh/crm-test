package com.acme.carrier.fault;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Service endpoints for switching fault modes; {@code dispatch} never calls them. */
@RestController
public class FaultsController {

    private final FaultModes modes;

    public FaultsController(FaultModes modes) {
        this.modes = modes;
    }

    @GetMapping("/admin/faults")
    Faults get() {
        return modes.current();
    }

    @PutMapping("/admin/faults")
    Faults set(@Valid @RequestBody Faults faults) {
        return modes.set(faults);
    }
}
