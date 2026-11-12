package com.acme.dispatch.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;

public interface AppUserRepository extends Repository<AppUser, UUID> {

    Optional<AppUser> findById(UUID id);

    @Query("SELECT * FROM app_user WHERE lower(username) = lower(:username)")
    Optional<AppUser> findByUsernameIgnoreCase(String username);

    @Query("""
            SELECT * FROM app_user
            WHERE service_id = :serviceId AND role = 'OPERATOR' AND enabled
            ORDER BY display_name""")
    List<AppUser> findEnabledOperators(UUID serviceId);
}
