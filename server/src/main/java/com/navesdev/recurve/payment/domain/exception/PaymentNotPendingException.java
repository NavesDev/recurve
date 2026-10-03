package com.navesdev.recurve.payment.domain.exception;

import java.util.UUID;

import com.navesdev.recurve.payment.domain.PaymentStatus;
import com.navesdev.recurve.shared.domain.exception.BusinessRuleException;

/** Only a charge still awaiting payment can fail, and a refunded one cannot be paid again. */
public class PaymentNotPendingException extends BusinessRuleException {

    public PaymentNotPendingException(UUID id, PaymentStatus status) {
        super("Payment %s is %s".formatted(id, status));
    }
}
