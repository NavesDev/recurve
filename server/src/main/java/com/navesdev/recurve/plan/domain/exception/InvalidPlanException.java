package com.navesdev.recurve.plan.domain.exception;

import com.navesdev.recurve.shared.domain.exception.BusinessRuleException;

/**
 * A plan or price attribute that cannot be: blank where text is required,
 * longer than its column, an amount that is not a positive two-place
 * value, a code that is not a currency. Thrown by {@code PlanValidator} on
 * the way into the entity, so a {@code Plan} never holds it.
 */
public class InvalidPlanException extends BusinessRuleException {

    public InvalidPlanException(String message) {
        super(message);
    }
}
