package com.acme.dispatch.auth;

import java.util.UUID;

/** A user as shown in pickers (e.g. choosing an assignee). */
public record UserSummary(UUID id, String username, String displayName) {

    static UserSummary of(AppUser user) {
        return new UserSummary(user.getId(), user.getUsername(), user.getDisplayName());
    }
}
