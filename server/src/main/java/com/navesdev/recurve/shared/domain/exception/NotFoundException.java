package com.navesdev.recurve.shared.domain.exception;

/** A resource that was addressed does not exist. Maps to 404. */
public abstract class NotFoundException extends RuntimeException {

    protected NotFoundException(String message) {
        super(message);
    }
}
