package com.navesdev.recurve.subscriber.domain.exception;

import com.navesdev.recurve.shared.domain.exception.BusinessRuleException;

/**
 * A subscriber attribute that cannot be: blank where text is required,
 * longer than its column, or an email that is not one. Thrown by
 * {@code SubscriberValidator} on the way into the entity, so a
 * {@code Subscriber} never holds it.
 */
public class InvalidSubscriberException extends BusinessRuleException {

    public InvalidSubscriberException(String message) {
        super(message);
    }
}
