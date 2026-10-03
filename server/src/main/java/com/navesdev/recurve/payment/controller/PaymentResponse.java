package com.navesdev.recurve.payment.controller;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.navesdev.recurve.payment.domain.PaymentStatus;
import com.navesdev.recurve.payment.domain.PaymentSummary;

/** A charge, with where the customer pays it. An amount goes out as a JSON number with two decimal places (NFR-06). */
public record PaymentResponse(
        UUID id,
        UUID subscriberId,
        BigDecimal amount,
        String currency,
        PaymentStatus status,
        Instant dueAt,
        Instant paidAt,
        Instant refundedAt,
        String externalId,
        String invoiceUrl,
        Instant createdAt) {

    /** The summary keeps the amount as text; it comes back exactly as stored. */
    public static PaymentResponse from(PaymentSummary summary) {
        return new PaymentResponse(
                summary.id(),
                summary.subscriberId(),
                new BigDecimal(summary.amount()),
                summary.currency(),
                summary.status(),
                summary.dueAt(),
                summary.paidAt(),
                summary.refundedAt(),
                summary.externalId(),
                summary.invoiceUrl(),
                summary.createdAt());
    }
}
