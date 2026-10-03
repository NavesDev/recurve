package com.navesdev.recurve.subscriber.domain.exception;

import com.navesdev.recurve.shared.domain.exception.BusinessRuleException;

/** BR-02: subscriber email is unique, canceled subscribers included. */
public class SubscriberEmailAlreadyInUseException extends BusinessRuleException {

    public SubscriberEmailAlreadyInUseException(String email) {
        super("Email %s is already in use by a subscriber".formatted(email));
    }
}
