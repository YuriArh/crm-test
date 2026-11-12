package com.acme.carrier.web;

import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Maps errors of the contract endpoints to {@code {code, message}}. */
@RestControllerAdvice
public class ErrorHandler {

    @ExceptionHandler(CarrierException.class)
    ResponseEntity<ErrorBody> handle(CarrierException ex) {
        return error(ex.code(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorBody> handle(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return error(ErrorCode.VALIDATION_FAILED, message);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ErrorBody> handle(HandlerMethodValidationException ex) {
        String message = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> result.getMethodParameter().getParameterName() + ": " + error.getDefaultMessage()))
                .sorted()
                .collect(Collectors.joining("; "));
        return error(ErrorCode.VALIDATION_FAILED, message);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorBody> handle(MethodArgumentTypeMismatchException ex) {
        return error(ErrorCode.VALIDATION_FAILED, ex.getName() + ": has a wrong format");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorBody> handle(HttpMessageNotReadableException ex) {
        return error(ErrorCode.VALIDATION_FAILED, "Request body is not valid JSON, has unknown fields or fields of a wrong type");
    }

    static ResponseEntity<ErrorBody> error(ErrorCode code, String message) {
        return ResponseEntity.status(code.status()).body(new ErrorBody(code, message));
    }
}
