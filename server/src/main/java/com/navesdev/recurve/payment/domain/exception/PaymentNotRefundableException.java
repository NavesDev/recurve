package com.navesdev.recurve.payment.domain.exception;

import java.util.UUID;

import com.navesdev.recurve.payment.domain.PaymentStatus;
import com.navesdev.recurve.shared.domain.exception.BusinessRuleException;

/** BR-08: only a paid payment can be refunded. */
public class PaymentNotRefundableException extends BusinessRuleException {

    public PaymentNotRefundableException(UUID id, PaymentStatus status) {
        super("Payment %s is %s; only a paid payment can be refunded".formatted(id, status));
    }
}
