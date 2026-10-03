package com.navesdev.recurve.payment.domain.exception;

import java.util.UUID;

import com.navesdev.recurve.shared.domain.exception.BusinessRuleException;

/** A cycle is paid once. */
public class PaymentAlreadyPaidException extends BusinessRuleException {

    public PaymentAlreadyPaidException(UUID id) {
        super("Payment %s is already paid".formatted(id));
    }
}
