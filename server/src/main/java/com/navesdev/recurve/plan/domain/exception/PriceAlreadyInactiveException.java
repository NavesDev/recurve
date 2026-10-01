package com.navesdev.recurve.plan.domain.exception;

import java.util.UUID;

import com.navesdev.recurve.shared.domain.exception.BusinessRuleException;

public class PriceAlreadyInactiveException extends BusinessRuleException {

    public PriceAlreadyInactiveException(UUID id) {
        super("Price %s is already inactive".formatted(id));
    }
}
