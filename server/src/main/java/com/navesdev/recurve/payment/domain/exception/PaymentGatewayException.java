package com.navesdev.recurve.payment.domain.exception;

import com.navesdev.recurve.shared.domain.exception.ExternalServiceException;

/** The payment gateway refused or failed a call. The message names the operation, never the key or the response body. */
public class PaymentGatewayException extends ExternalServiceException {

    public PaymentGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
