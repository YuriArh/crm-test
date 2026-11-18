package com.acme.dispatch.auth;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.DispatcherType;

import com.acme.dispatch.support.IntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.DelegatingFilterProxyRegistrationBean;

/**
 * MockMvc never forwards an error to {@code /error}, so what happens on the real error page is checked through the
 * registration of the session filter (docs/specs/jdbc-sessions.md §2.6).
 */
@IntegrationTest
class SessionFilterRegistrationTests {

    @Autowired
    @Qualifier("sessionRepositoryFilterRegistration")
    DelegatingFilterProxyRegistrationBean registration;

    /**
     * When the session store fails (the database is down), the container forwards the error to {@code /error}. If the
     * session filter ran there too, it would read the session again, fail again, and the client would get the
     * container's HTML page instead of Problem Details. The error page needs no session.
     */
    @Test
    void theErrorPageDoesNotReadTheSessionAgain() {
        assertThat(registration.determineDispatcherTypes())
                .contains(DispatcherType.REQUEST)
                .doesNotContain(DispatcherType.ERROR);
    }
}
