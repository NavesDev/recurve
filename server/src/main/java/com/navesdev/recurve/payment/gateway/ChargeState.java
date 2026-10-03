package com.navesdev.recurve.payment.gateway;

/**
 * Where a charge stands at the gateway, in the few states Recurve acts on.
 * A gateway implementation maps its own vocabulary onto these; anything
 * Recurve has no rule for — a refund in progress, a chargeback — is
 * {@link #OTHER}, and changes nothing.
 */
public enum ChargeState {

    /** Awaiting payment. */
    PENDING,
    /** Paid, by whatever means the customer chose. */
    PAID,
    /** Due and unpaid. */
    OVERDUE,
    /** Given back in full. */
    REFUNDED,
    /** Anything else. */
    OTHER
}
