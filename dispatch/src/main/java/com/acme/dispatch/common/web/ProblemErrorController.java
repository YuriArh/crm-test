package com.acme.dispatch.common.web;

import java.net.URI;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Where the servlet container sends an error raised outside the controllers (a filter, the session store, the
 * container itself). Without this the Spring Boot default answers with its own JSON ({@code timestamp},
 * {@code error}, {@code path}) and no {@code code}, unlike every other answer of the API.
 * <p>
 * The status comes from the container's error attributes; the body is Problem Details with the project's {@code code}
 * and never carries the exception text. Errors inside controllers are still answered by {@link ApiExceptionHandler}.
 */
@Controller
public class ProblemErrorController implements ErrorController {

    private static final Logger log = LoggerFactory.getLogger(ProblemErrorController.class);

    @RequestMapping("/error")
    ResponseEntity<ProblemDetail> error(HttpServletRequest request) {
        int status = statusOf(request);
        String uri = (String) request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        // Only what the container forwarded is an error worth the log; a direct hit of this public page is not.
        if (status >= 500 && request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) != null) {
            Throwable cause = (Throwable) request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
            log.error("Error {} outside the controllers at {}", status, uri, cause);
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail(status));
        problem.setTitle(titleOf(status));
        problem.setProperty(Problems.CODE, codeFor(status));
        if (uri != null) {
            try {
                problem.setInstance(URI.create(uri));
            } catch (IllegalArgumentException ignored) {
                // The container's request URI is already encoded; an odd one is simply left out.
            }
        }
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    /** The machine-readable code for an HTTP status: the project's own where it has one, else the status name. */
    static String codeFor(int status) {
        if (status >= 500) {
            return ErrorCode.INTERNAL_ERROR.name();
        }
        return switch (status) {
            case 401 -> ErrorCode.UNAUTHENTICATED.name();
            case 403 -> ErrorCode.FORBIDDEN.name();
            case 404 -> ErrorCode.NOT_FOUND.name();
            default -> ApiExceptionHandler.statusCodeName(HttpStatusCode.valueOf(status));
        };
    }

    private static int statusOf(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (attribute instanceof Integer status && status >= 400 && status <= 599) {
            return status;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR.value();
    }

    private static String detail(int status) {
        if (status >= 500) {
            return "Unexpected error; see the server log";
        }
        return switch (status) {
            case 401 -> "Sign in first";
            case 403 -> "Access denied";
            case 404 -> "Not found";
            default -> titleOf(status);
        };
    }

    private static String titleOf(int status) {
        HttpStatus known = HttpStatus.resolve(status);
        return known != null ? known.getReasonPhrase() : "Error " + status;
    }
}
