package com.navesdev.recurve.plan.domain.exception;

import java.util.UUID;

import com.navesdev.recurve.shared.domain.exception.BusinessRuleException;

public class PlanAlreadyInactiveException extends BusinessRuleException {

    public PlanAlreadyInactiveException(UUID id) {
        super("Plan %s is already inactive".formatted(id));
    }
}
