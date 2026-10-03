package com.navesdev.recurve.plan.domain.exception;

import java.util.UUID;

import com.navesdev.recurve.shared.domain.exception.BusinessRuleException;

/** BR-04, FR-02.3: only a price in force can be replaced or take a new subscriber. */
public class PriceInactiveException extends BusinessRuleException {

    public PriceInactiveException(UUID id) {
        super("Price %s is inactive".formatted(id));
    }
}
