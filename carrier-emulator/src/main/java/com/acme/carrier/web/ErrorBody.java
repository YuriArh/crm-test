package com.acme.carrier.web;

/** Error response of the contract: a plain {@code {code, message}}, not Problem Details. */
public record ErrorBody(ErrorCode code, String message) {
}
