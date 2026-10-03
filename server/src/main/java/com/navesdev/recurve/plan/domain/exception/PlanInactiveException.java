package com.navesdev.recurve.plan.domain.exception;

import java.util.UUID;

import com.navesdev.recurve.shared.domain.exception.BusinessRuleException;

/** FR-02.4: an inactive plan accepts no new price and no replacement. */
public class PlanInactiveException extends BusinessRuleException {

    public PlanInactiveException(UUID id) {
        super("Plan %s is inactive and accepts no new price".formatted(id));
    }
}
