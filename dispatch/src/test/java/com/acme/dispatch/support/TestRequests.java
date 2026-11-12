package com.acme.dispatch.support;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import com.acme.dispatch.auth.AppUserRepository;
import com.acme.dispatch.auth.CurrentUser;
import com.acme.dispatch.request.CreateRequestBody;
import com.acme.dispatch.request.RequestCard;
import com.acme.dispatch.request.RequestService;

import org.springframework.stereotype.Component;

/** Creates requests through the real business operation, so each has its PENDING dispatch operation. */
@Component
public class TestRequests {

    /** After {@link TestClockConfiguration#START} (2026-11-12), so creation is always valid. */
    public static final LocalDate DEADLINE = LocalDate.of(2026, 11, 20);

    private final RequestService requests;
    private final AppUserRepository users;

    public TestRequests(RequestService requests, AppUserRepository users) {
        this.requests = requests;
        this.users = users;
    }

    public RequestCard create(String weightKg) {
        return create("Pallets", weightKg, "Main st 1");
    }

    public RequestCard create(String cargo, String weightKg, String address) {
        CurrentUser initiator = CurrentUser.from(users.findByUsernameIgnoreCase(DemoData.NORTH_INITIATOR_LOGIN)
                .orElseThrow());
        return requests.create(initiator, UUID.randomUUID().toString(),
                new CreateRequestBody(cargo, new BigDecimal(weightKg), address, DEADLINE)).body();
    }
}
