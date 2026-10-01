package com.navesdev.recurve.plan.domain.exception;

import com.navesdev.recurve.plan.domain.BillingInterval;
import com.navesdev.recurve.shared.domain.exception.BusinessRuleException;

/** BR-03: at most one active price per cycle and currency. Changing it is a replace (BR-04). */
public class PriceAlreadyActiveException extends BusinessRuleException {

    public PriceAlreadyActiveException(BillingInterval interval, String currency) {
        super("The plan already has an active %s price in %s; replace it instead".formatted(interval, currency));
    }
}
