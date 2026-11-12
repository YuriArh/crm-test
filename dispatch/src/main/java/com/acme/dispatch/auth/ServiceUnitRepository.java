package com.acme.dispatch.auth;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

public interface ServiceUnitRepository extends Repository<ServiceUnit, UUID> {

    Optional<ServiceUnit> findById(UUID id);
}
