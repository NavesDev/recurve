package com.navesdev.recurve.shared.controller;

import java.time.Instant;
import java.util.List;

/** Error body returned by {@link GlobalExceptionHandler}. */
public record ApiError(int status, String error, String message, Instant timestamp, List<FieldError> fieldErrors) {

    public record FieldError(String field, String message) {
    }

    public static ApiError of(int status, String error, String message, Instant timestamp) {
        return new ApiError(status, error, message, timestamp, List.of());
    }
}
