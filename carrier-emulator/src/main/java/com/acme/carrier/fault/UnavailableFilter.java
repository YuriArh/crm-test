package com.acme.carrier.fault;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.acme.carrier.web.ErrorBody;
import com.acme.carrier.web.ErrorCode;

import tools.jackson.databind.json.JsonMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Fault mode {@code unavailable}: while it is on, the exchange endpoints ({@code POST /operations},
 * {@code GET /operations/{operationId}}, {@code GET /results}) answer {@code 503 UNAVAILABLE} before the request
 * body is read, so nothing is stored and {@code receivedCount} does not change. Service endpoints
 * ({@code /admin/**}, actuator) are never affected, and the executor keeps working.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UnavailableFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(UnavailableFilter.class);

    static final String MESSAGE = "Carrier is temporarily unavailable";

    private final FaultModes modes;
    private final JsonMapper json;

    public UnavailableFilter(FaultModes modes, JsonMapper json) {
        this.modes = modes;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isExchangeEndpoint(request.getMethod(), pathWithinApplication(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!modes.current().unavailable()) {
            chain.doFilter(request, response);
            return;
        }
        log.warn("Fault unavailable: refused {} {} with 503", request.getMethod(), pathWithinApplication(request));
        ErrorCode code = ErrorCode.UNAVAILABLE;
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(), new ErrorBody(code, MESSAGE));
    }

    static boolean isExchangeEndpoint(String method, String path) {
        return switch (method) {
            case "POST" -> path.equals("/operations");
            case "GET" -> path.equals("/results")
                    || (path.startsWith("/operations/") && path.length() > "/operations/".length()
                            && path.indexOf('/', "/operations/".length()) < 0);
            default -> false;
        };
    }

    private static String pathWithinApplication(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }
}
