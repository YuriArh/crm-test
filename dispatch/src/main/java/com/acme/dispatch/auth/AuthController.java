package com.acme.dispatch.auth;

import com.acme.dispatch.common.web.ApiException;
import com.acme.dispatch.common.web.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfLogoutHandler;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DeferredCsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Session login for the SPA. Form login is disabled in {@link SecurityConfig}; this controller does
 * what its filter would: authenticate, change the session id, save the context, rotate the CSRF token.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private static final String BAD_CREDENTIALS_DETAIL = "Wrong username or password";

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final CookieCsrfTokenRepository csrfTokenRepository;
    private final ServiceUnitRepository services;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();
    private final SessionAuthenticationStrategy sessionFixation = new ChangeSessionIdAuthenticationStrategy();

    public AuthController(AuthenticationManager authenticationManager,
                          SecurityContextRepository securityContextRepository,
                          CookieCsrfTokenRepository csrfTokenRepository,
                          ServiceUnitRepository services) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.csrfTokenRepository = csrfTokenRepository;
        this.services = services;
    }

    /** Sets the {@code XSRF-TOKEN} cookie and returns the same raw token (the value to send in the header). */
    @GetMapping("/csrf")
    CsrfResponse csrf(HttpServletRequest request, CsrfToken maskedToken) {
        // The request attribute CsrfToken is XOR-masked; the header must carry the raw (cookie) value,
        // which the CsrfFilter exposes as the repository's deferred token. The header name is our constant
        // (the repository is configured with it) rather than the token's, which tests may replace.
        CsrfToken token = request.getAttribute(DeferredCsrfToken.class.getName()) instanceof DeferredCsrfToken deferred
                ? deferred.get()
                : maskedToken;
        return new CsrfResponse(SecurityConfig.CSRF_HEADER, token.getToken());
    }

    @PostMapping("/login")
    MeResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
                     HttpServletResponse response) {
        Authentication result;
        try {
            result = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(body.username(), body.password()));
        } catch (AuthenticationException ex) {
            // Unknown user, wrong password and disabled account look the same to the client.
            log.info("Login failed for username '{}': {}", body.username(), ex.getClass().getSimpleName());
            throw new ApiException(ErrorCode.BAD_CREDENTIALS, BAD_CREDENTIALS_DETAIL);
        }

        CurrentUser user = ((CurrentUser) result.getPrincipal()).withoutPassword();
        Authentication authentication =
                UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities());

        sessionFixation.onAuthentication(authentication, request, response);
        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        contextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        rotateCsrfToken(request, response);

        log.info("Login succeeded for username '{}'", user.username());
        return me(user);
    }

    /** Invalidates the session and clears the CSRF cookie; the client fetches a new token afterwards. */
    @PostMapping("/logout")
    ResponseEntity<Void> logout(@AuthenticationPrincipal CurrentUser user, HttpServletRequest request,
                                HttpServletResponse response) {
        Authentication authentication = contextHolder.getContext().getAuthentication();
        SecurityContextLogoutHandler logoutHandler = new SecurityContextLogoutHandler();
        logoutHandler.setSecurityContextRepository(securityContextRepository);
        logoutHandler.logout(request, response, authentication);
        new CsrfLogoutHandler(csrfTokenRepository).logout(request, response, authentication);
        log.info("Logout for username '{}'", user.username());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    MeResponse me(@AuthenticationPrincipal CurrentUser user) {
        ServiceUnit service = services.findById(user.serviceId())
                .orElseThrow(() -> new IllegalStateException("No service " + user.serviceId()));
        return MeResponse.of(user, service);
    }

    /**
     * Issues a new CSRF token after login so a token seen before authentication is not reused.
     * Only when the client sent a token cookie; the client re-reads the {@code XSRF-TOKEN} cookie.
     */
    private void rotateCsrfToken(HttpServletRequest request, HttpServletResponse response) {
        if (csrfTokenRepository.loadToken(request) != null) {
            csrfTokenRepository.saveToken(csrfTokenRepository.generateToken(request), request, response);
        }
    }
}
