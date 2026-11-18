package com.acme.dispatch.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.core.convert.ConversionService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;

class SessionStoreConfigurationTest {

    private final ConversionService conversion =
            SessionStoreConfiguration.conversionService(getClass().getClassLoader());

    @Test
    void roundTripsTheSecurityContext() {
        CurrentUser user = new CurrentUser(UUID.randomUUID(), "north.initiator", "Инициатор (Север)", null,
                Role.INITIATOR, UUID.randomUUID(), true);
        SecurityContext context = new SecurityContextImpl(
                UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));

        byte[] bytes = conversion.convert(context, byte[].class);
        Object restored = conversion.convert(bytes, Object.class);

        assertThat(restored).isInstanceOf(SecurityContext.class);
        assertThat(((SecurityContext) restored).getAuthentication().getPrincipal()).isEqualTo(user);
    }

    @Test
    void unreadableBytesBecomeNull() {
        assertThat(conversion.convert(new byte[] {0, (byte) 0xff, 1}, Object.class)).isNull();
    }

    @Test
    void aTruncatedStreamBecomesNull() {
        byte[] bytes = conversion.convert(new SecurityContextImpl(), byte[].class);
        byte[] truncated = java.util.Arrays.copyOf(bytes, bytes.length / 2);

        assertThat(conversion.convert(truncated, Object.class)).isNull();
    }
}
