package com.acme.dispatch.auth;

import java.util.UUID;

/** The signed-in user, as returned by {@code /api/auth/me} and {@code /api/auth/login}. */
public record MeResponse(UUID id, String username, String displayName, Role role, ServiceRef service) {

    public record ServiceRef(UUID id, String code, String name) {

        static ServiceRef of(ServiceUnit service) {
            return new ServiceRef(service.getId(), service.getCode(), service.getName());
        }
    }

    static MeResponse of(CurrentUser user, ServiceUnit service) {
        return new MeResponse(user.id(), user.username(), user.displayName(), user.role(), ServiceRef.of(service));
    }
}
