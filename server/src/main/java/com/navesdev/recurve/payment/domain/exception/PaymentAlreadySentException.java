package com.navesdev.recurve.payment.domain.exception;

import java.util.UUID;

import com.navesdev.recurve.shared.domain.exception.BusinessRuleException;

/** A charge is one charge at the gateway, for good. */
public class PaymentAlreadySentException extends BusinessRuleException {

    public PaymentAlreadySentException(UUID id) {
        super("Payment %s was already sent to the gateway".formatted(id));
    }
}
