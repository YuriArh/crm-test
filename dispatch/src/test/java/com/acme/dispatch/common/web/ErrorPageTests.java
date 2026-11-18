package com.acme.dispatch.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.dispatch.support.Contract;
import com.acme.dispatch.support.IntegrationTest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The page {@code /error} is where the servlet container sends an error raised outside the controllers (a filter, the
 * session store). MockMvc does not forward there, so these tests request it directly with the attributes the container
 * sets; the whole chain is checked on the stand (docs/specs/jdbc-sessions.md §3).
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class ErrorPageTests {

    private static final String STATUS = "jakarta.servlet.error.status_code";
    private static final String EXCEPTION = "jakarta.servlet.error.exception";
    private static final String URI = "jakarta.servlet.error.request_uri";

    @Autowired
    MockMvc mockMvc;

    @Test
    void serverErrorIsLoggedWithItsCause(CapturedOutput output) throws Exception {
        mockMvc.perform(errorPage()
                        .requestAttr(STATUS, 503)
                        .requestAttr(URI, "/api/requests")
                        .requestAttr(EXCEPTION, new IllegalStateException("store is down")))
                .andExpect(status().isServiceUnavailable());

        assertThat(output).contains("Error 503 outside the controllers at /api/requests")
                .contains("store is down");
    }

    @Test
    void directHitWithoutContainerAttributesIsNotLoggedAsAnError(CapturedOutput output) throws Exception {
        mockMvc.perform(errorPage()).andExpect(status().isInternalServerError());

        assertThat(output).doesNotContain("outside the controllers");
    }

    @Test
    void serverErrorIsProblemDetailsWithoutInternals() throws Exception {
        mockMvc.perform(errorPage()
                        .requestAttr(STATUS, 500)
                        .requestAttr(URI, "/api/requests")
                        .requestAttr(EXCEPTION, new IllegalStateException("secret jdbc url jdbc:postgresql://db")))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.instance").value("/api/requests"))
                .andExpect(content().string(not(containsString("secret"))))
                .andExpect(content().string(not(containsString("jdbc"))));
    }

    @Test
    void clientErrorKeepsItsStatusAndGetsTheStatusName() throws Exception {
        mockMvc.perform(errorPage().requestAttr(STATUS, 405))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void notFoundIsNotFound() throws Exception {
        mockMvc.perform(errorPage().requestAttr(STATUS, 404))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void errorPageWithoutStatusIsInternalError() throws Exception {
        mockMvc.perform(errorPage())
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    private static MockHttpServletRequestBuilder errorPage() {
        return get("/error").header(Contract.SKIP_HEADER, "/error is an internal error page, not an API path");
    }
}
