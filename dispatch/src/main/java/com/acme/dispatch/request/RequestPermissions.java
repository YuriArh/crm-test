package com.acme.dispatch.request;

import java.util.UUID;

import com.acme.dispatch.auth.CurrentUser;

/**
 * Access policy for delivery requests (NF02). Pure functions over ids, so the whole
 * role × owner × service matrix is unit-testable. Scope comes only from the authenticated user,
 * never from request parameters.
 */
public final class RequestPermissions {

    private RequestPermissions() {
    }

    /** Initiator: own requests. Operator: requests of their service. */
    public static boolean canView(CurrentUser user, UUID ownerId, UUID serviceId) {
        if (user.isOperator()) {
            return user.serviceId().equals(serviceId);
        }
        return user.isInitiator() && user.id().equals(ownerId);
    }

    public static boolean canCreate(CurrentUser user) {
        return user.isInitiator();
    }

    /** Whoever can see a request may comment on it: the owner or an operator of the service. */
    public static boolean canComment(CurrentUser user, UUID ownerId, UUID serviceId) {
        return canView(user, ownerId, serviceId);
    }

    /** Only an operator of the request's service assigns it. */
    public static boolean canAssign(CurrentUser user, UUID serviceId) {
        return user.isOperator() && user.serviceId().equals(serviceId);
    }
}
