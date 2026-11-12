package com.acme.carrier.web;

public class CarrierException extends RuntimeException {

    private final ErrorCode code;

    public CarrierException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
