package com.navesdev.recurve.payment.domain;

/** Where a charge stands (FR-04). */
public enum PaymentStatus {

    /** Created, awaiting payment. */
    PENDING,
    /** Confirmed; the payment date is recorded. */
    PAID,
    /** Declined by the gateway, or due and unpaid. May still be paid late. */
    FAILED,
    /** Reversed after being paid (BR-08). */
    REFUNDED
}
