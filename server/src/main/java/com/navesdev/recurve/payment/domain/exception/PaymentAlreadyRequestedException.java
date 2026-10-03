package com.navesdev.recurve.payment.domain.exception;

import java.time.Instant;
import java.util.UUID;

import com.navesdev.recurve.shared.domain.exception.BusinessRuleException;

/** One charge per subscriber per cycle. */
public class PaymentAlreadyRequestedException extends BusinessRuleException {

    public PaymentAlreadyRequestedException(UUID subscriberId, Instant dueAt) {
        super("A charge for subscriber %s due %s was already requested".formatted(subscriberId, dueAt));
    }
}
