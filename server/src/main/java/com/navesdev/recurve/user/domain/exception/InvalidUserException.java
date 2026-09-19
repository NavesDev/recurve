package com.navesdev.recurve.user.domain.exception;

import com.navesdev.recurve.shared.domain.exception.BusinessRuleException;

/**
 * An operator attribute that cannot be: blank where text is required,
 * longer than its column, or an email that is not one. Thrown by
 * {@code UserValidator} on the way into the entity, so a {@code User}
 * never holds it — whoever built it, through the API or not.
 */
public class InvalidUserException extends BusinessRuleException {

    public InvalidUserException(String message) {
        super(message);
    }
}
