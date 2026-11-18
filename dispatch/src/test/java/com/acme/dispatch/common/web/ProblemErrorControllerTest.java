package com.acme.dispatch.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProblemErrorControllerTest {

    @Test
    void serverErrorsAreInternalError() {
        assertThat(ProblemErrorController.codeFor(500)).isEqualTo("INTERNAL_ERROR");
        assertThat(ProblemErrorController.codeFor(502)).isEqualTo("INTERNAL_ERROR");
        assertThat(ProblemErrorController.codeFor(503)).isEqualTo("INTERNAL_ERROR");
    }

    @Test
    void ownCodesForAuthAndNotFound() {
        assertThat(ProblemErrorController.codeFor(401)).isEqualTo("UNAUTHENTICATED");
        assertThat(ProblemErrorController.codeFor(403)).isEqualTo("FORBIDDEN");
        assertThat(ProblemErrorController.codeFor(404)).isEqualTo("NOT_FOUND");
    }

    @Test
    void otherClientErrorsAreTheStatusName() {
        assertThat(ProblemErrorController.codeFor(400)).isEqualTo("BAD_REQUEST");
        assertThat(ProblemErrorController.codeFor(405)).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(ProblemErrorController.codeFor(415)).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
        assertThat(ProblemErrorController.codeFor(499)).isEqualTo("HTTP_499");
    }
}
