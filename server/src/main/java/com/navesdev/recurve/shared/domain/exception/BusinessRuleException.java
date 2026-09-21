package com.navesdev.recurve.shared.domain.exception;

/** A business rule was violated. The request was well formed. Maps to 422. */
public abstract class BusinessRuleException extends RuntimeException {

    protected BusinessRuleException(String message) {
        super(message);
    }
}
