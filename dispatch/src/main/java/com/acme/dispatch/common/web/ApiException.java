package com.acme.dispatch.common.web;

import java.util.LinkedHashMap;
import java.util.Map;

/** A business error that maps directly to a Problem Details response. */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> properties;

    public ApiException(ErrorCode code, String detail) {
        this(code, detail, Map.of());
    }

    public ApiException(ErrorCode code, String detail, Map<String, Object> properties) {
        super(detail);
        this.code = code;
        this.properties = new LinkedHashMap<>(properties);
    }

    public static ApiException notFound(String detail) {
        return new ApiException(ErrorCode.NOT_FOUND, detail);
    }

    public static ApiException forbidden(String detail) {
        return new ApiException(ErrorCode.FORBIDDEN, detail);
    }

    public ErrorCode code() {
        return code;
    }

    /** Extra Problem Details members, e.g. the current state on a version conflict. */
    public Map<String, Object> properties() {
        return properties;
    }
}
