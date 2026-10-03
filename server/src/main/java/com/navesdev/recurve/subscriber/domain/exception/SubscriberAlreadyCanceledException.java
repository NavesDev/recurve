package com.navesdev.recurve.subscriber.domain.exception;

import java.util.UUID;

import com.navesdev.recurve.shared.domain.exception.BusinessRuleException;

/** FR-03.3: a subscription ends once. */
public class SubscriberAlreadyCanceledException extends BusinessRuleException {

    public SubscriberAlreadyCanceledException(UUID id) {
        super("Subscriber %s is already canceled".formatted(id));
    }
}
