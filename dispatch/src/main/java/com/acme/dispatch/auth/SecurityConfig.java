package com.acme.dispatch.auth;

import com.acme.dispatch.common.web.ErrorCode;
import com.acme.dispatch.common.web.ProblemResponseWriter;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfException;

/**
 * Session-cookie authentication for the SPA (same origin), CSRF via the {@code XSRF-TOKEN}
 * cookie / {@code X-XSRF-TOKEN} header, and Problem Details for 401/403 — no redirects,
 * no login page.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    /** Endpoints reachable without a session. */
    static final String[] PUBLIC_ENDPOINTS = {
            "/actuator/health", "/actuator/health/**",
            "/api/system/info",
            "/api/auth/csrf", "/api/auth/login",
            "/error"
    };

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ProblemResponseWriter problems) throws Exception {
        http
                .csrf(csrf -> csrf.spa())
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

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
