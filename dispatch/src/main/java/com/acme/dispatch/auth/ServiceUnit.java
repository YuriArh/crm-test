package com.acme.dispatch.auth;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.relational.core.mapping.Table;

/** A service (organizational unit) — the access scope of users and requests. Reference data. */
@Table("service")
public class ServiceUnit {

    @Id
    private UUID id;
    private String code;
    private String name;
    private Instant createdAt;

    @PersistenceCreator
    ServiceUnit() {
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
