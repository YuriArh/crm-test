package com.acme.dispatch.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatusCode;

class ApiExceptionHandlerTest {

    @Test
    void frameworkErrorCodeIsTheHttpStatusName() {
        assertThat(ApiExceptionHandler.statusCodeName(HttpStatusCode.valueOf(405))).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(ApiExceptionHandler.statusCodeName(HttpStatusCode.valueOf(404))).isEqualTo("NOT_FOUND");
        assertThat(ApiExceptionHandler.statusCodeName(HttpStatusCode.valueOf(415))).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void unknownStatusFallsBackToTheNumber() {
        assertThat(ApiExceptionHandler.statusCodeName(HttpStatusCode.valueOf(599))).isEqualTo("HTTP_599");
    }
}
