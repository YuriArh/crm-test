package com.acme.dispatch.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

class CurrentUserTest {

    static final String HASH = "$2y$10$abcdefghijklmnopqrstuvABCDEFGHIJKLMNOPQRSTUVWXYZ01234";

    static CurrentUser user(Role role) {
        return new CurrentUser(UUID.randomUUID(), "north.user", "North User", HASH, role, UUID.randomUUID(), true);
    }

    @Test
    void withoutPasswordDropsTheHashAndKeepsTheRest() {
        CurrentUser user = user(Role.INITIATOR);

        CurrentUser safe = user.withoutPassword();

        assertThat(safe.passwordHash()).isNull();
        assertThat(safe.getPassword()).isNull();
        assertThat(safe.id()).isEqualTo(user.id());
        assertThat(safe.username()).isEqualTo(user.username());
        assertThat(safe.displayName()).isEqualTo(user.displayName());
        assertThat(safe.role()).isEqualTo(user.role());
        assertThat(safe.serviceId()).isEqualTo(user.serviceId());
        assertThat(safe.isEnabled()).isTrue();
    }

    @Test
    void authoritiesArePrefixedRole() {
        assertThat(user(Role.INITIATOR).getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_INITIATOR");
        assertThat(user(Role.OPERATOR).getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_OPERATOR");
    }

    @Test
    void toStringDoesNotContainTheHash() {
        CurrentUser user = user(Role.OPERATOR);

        assertThat(user.toString()).doesNotContain(HASH).doesNotContain("$2y$").contains("north.user");
    }

    @Test
    void rolePredicates() {
        assertThat(user(Role.OPERATOR).isOperator()).isTrue();
        assertThat(user(Role.OPERATOR).isInitiator()).isFalse();
        assertThat(user(Role.INITIATOR).isInitiator()).isTrue();
        assertThat(user(Role.INITIATOR).isOperator()).isFalse();
    }
}
