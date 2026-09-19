package com.navesdev.recurve.shared.domain.exception;

/** An external service failed. Maps to 502. */
public abstract class ExternalServiceException extends RuntimeException {

    protected ExternalServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
