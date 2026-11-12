package com.acme.dispatch.auth;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The authenticated principal. Never serialized to API responses — controllers map it to DTOs.
 *
 * @param passwordHash present only while authenticating; the principal stored in the session has it removed
 */
public record CurrentUser(
        UUID id,
        String username,
        String displayName,
        String passwordHash,
        Role role,
        UUID serviceId,
        boolean enabled) implements UserDetails {

    public static CurrentUser from(AppUser user) {
        return new CurrentUser(user.getId(), user.getUsername(), user.getDisplayName(), user.getPasswordHash(),
                user.getRole(), user.getServiceId(), user.isEnabled());
    }

    public CurrentUser withoutPassword() {
        return new CurrentUser(id, username, displayName, null, role, serviceId, enabled);
    }

    public boolean isOperator() {
        return role == Role.OPERATOR;
    }

    public boolean isInitiator() {
        return role == Role.INITIATOR;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String toString() {
        // Keep the hash out of logs and debug output.
        return "CurrentUser[id=" + id + ", username=" + username + ", role=" + role + ", serviceId=" + serviceId + "]";
    }
}
