package com.acme.dispatch.auth;

import com.acme.dispatch.common.web.ErrorCode;
import com.acme.dispatch.common.web.ProblemResponseWriter;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;

/**
 * Session-cookie authentication for the SPA (same origin), CSRF via the {@code XSRF-TOKEN}
 * cookie / {@code X-XSRF-TOKEN} header, and Problem Details for 401/403 — no redirects,
 * no login page.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    /** Endpoints reachable without a session. */
    /** CSRF cookie the SPA reads and the header it echoes the value in. */
    static final String CSRF_COOKIE = "XSRF-TOKEN";
    static final String CSRF_HEADER = "X-XSRF-TOKEN";

    static final String[] PUBLIC_ENDPOINTS = {
            "/actuator/health", "/actuator/health/**",
            "/api/system/info",
            "/api/auth/csrf", "/api/auth/login",
            "/error"
    };

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ProblemResponseWriter problems,
                                    SecurityContextRepository securityContextRepository,
                                    CookieCsrfTokenRepository csrfTokenRepository) throws Exception {
        http
                // spa() already uses a cookie repository; we pass our own bean so AuthController
                // rotates the token in the very same repository after login.
                .csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokenRepository))
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .anyRequest().authenticated())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, ex) ->
                                problems.write(request, response, ErrorCode.UNAUTHENTICATED, "Sign in first"))
                        .accessDeniedHandler((request, response, ex) ->
                                problems.write(request, response, ErrorCode.FORBIDDEN,
                                        ex instanceof CsrfException
                                                ? "Missing or invalid CSRF token"
                                                : "Access denied")))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);
        return http.build();
    }

    /**
     * Where the filter chain loads the security context from. {@link AuthController} saves the
     * context here explicitly after login: since Spring Security 6 manual logins are not auto-saved.
     */
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(),
                new HttpSessionSecurityContextRepository());
    }

    /** The {@code XSRF-TOKEN} cookie (readable by the SPA) / {@code X-XSRF-TOKEN} header. */
    @Bean
    CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieName(CSRF_COOKIE);
        repository.setHeaderName(CSRF_HEADER);
        return repository;
    }

    /** Username/password check for {@code POST /api/auth/login}. */
    @Bean
    AuthenticationManager authenticationManager(AppUserDetailsService userDetailsService,
                                                PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
