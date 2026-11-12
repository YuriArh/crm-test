package com.acme.dispatch.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import com.acme.dispatch.auth.AppUserRepository;
import com.acme.dispatch.auth.CurrentUser;

import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Runs a MockMvc request as a real demo user loaded from the database. */
public final class TestSecurity {

    private TestSecurity() {
    }

    public static RequestPostProcessor asUser(AppUserRepository users, String username) {
        CurrentUser principal = users.findByUsernameIgnoreCase(username)
                .map(CurrentUser::from)
                .map(CurrentUser::withoutPassword)
                .orElseThrow(() -> new IllegalArgumentException("No demo user " + username));
        return user(principal);
    }
}
