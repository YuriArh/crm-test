package com.acme.dispatch.common.web;

import java.util.Map;

import org.springframework.http.ProblemDetail;

/** Builds Problem Details (RFC 9457) with the project's {@code code} member. */
public final class Problems {

    public static final String CODE = "code";

    private Problems() {
    }

    public static ProblemDetail of(ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setTitle(code.title());
        problem.setProperty(CODE, code.name());
        return problem;
    }

    public static ProblemDetail of(ErrorCode code, String detail, Map<String, Object> properties) {
        ProblemDetail problem = of(code, detail);
        properties.forEach(problem::setProperty);
        return problem;
    }
}
