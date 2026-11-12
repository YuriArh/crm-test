package com.acme.dispatch.carrier;

/** The carrier could not be read (connection error, timeout or a non-2xx answer); try again later. */
public class CarrierUnavailableException extends RuntimeException {

    public CarrierUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
