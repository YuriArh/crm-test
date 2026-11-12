package com.acme.dispatch.auth;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;

/** Application user. Created only by migrations (demo data); read-only for the application. */
public class AppUser {

    @Id
    private UUID id;
    private String username;
    private String displayName;
    private String passwordHash;
    private Role role;
    private UUID serviceId;
    private boolean enabled;
    private Instant createdAt;

    @PersistenceCreator
    AppUser() {
    }

    public UUID getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public UUID getServiceId() {
        return serviceId;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
