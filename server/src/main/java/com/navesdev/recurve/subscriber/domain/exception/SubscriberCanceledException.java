package com.navesdev.recurve.subscriber.domain.exception;

import java.util.UUID;

import com.navesdev.recurve.shared.domain.exception.BusinessRuleException;

/** FR-03.7: a canceled subscriber is final; nothing about it changes. */
public class SubscriberCanceledException extends BusinessRuleException {

    public SubscriberCanceledException(UUID id) {
        super("Subscriber %s is canceled and cannot be changed".formatted(id));
    }
}
