package com.navesdev.recurve.plan.domain.exception;

import java.util.UUID;

import com.navesdev.recurve.shared.domain.exception.BusinessRuleException;

/** BR-04: only a price in force can be replaced. */
public class PriceInactiveException extends BusinessRuleException {

    public PriceInactiveException(UUID id) {
        super("Price %s is inactive and cannot be replaced".formatted(id));
    }
}
