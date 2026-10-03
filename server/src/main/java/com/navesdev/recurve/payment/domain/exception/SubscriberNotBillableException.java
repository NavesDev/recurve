package com.navesdev.recurve.payment.domain.exception;

import java.util.UUID;

import com.navesdev.recurve.shared.domain.exception.BusinessRuleException;

/** BR-07, FR-04.7: a canceled subscriber is never charged, and one without a tax document cannot be sent to the gateway. */
public class SubscriberNotBillableException extends BusinessRuleException {

    public SubscriberNotBillableException(UUID subscriberId) {
        super("Subscriber %s cannot be charged: it is canceled or has no document".formatted(subscriberId));
    }
}
