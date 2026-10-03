package com.navesdev.recurve.payment.domain.exception;

import java.util.UUID;

import com.navesdev.recurve.shared.domain.exception.NotFoundException;

/** A payment Recurve does not hold. */
public class PaymentNotFoundException extends NotFoundException {

    public PaymentNotFoundException(UUID id) {
        super("Payment %s not found".formatted(id));
    }
}
