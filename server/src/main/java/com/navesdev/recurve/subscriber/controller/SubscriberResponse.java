package com.navesdev.recurve.subscriber.controller;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.navesdev.recurve.plan.domain.BillingInterval;
import com.navesdev.recurve.subscriber.domain.SubscriberStatus;
import com.navesdev.recurve.subscriber.domain.SubscriberSummary;

/**
 * A subscriber with what it pays: the price's plan, amount, currency and
 * cycle. One shape for one subscriber and for the listing. An amount goes
 * out as a JSON number with its two decimal places (NFR-06).
 */
public record SubscriberResponse(
        UUID id,
        String name,
        String email,
        SubscriberStatus status,
        UUID planId,
        UUID planPriceId,
        BigDecimal price,
        String currency,
        BillingInterval interval,
        Instant startedAt,
        Instant nextBillingAt,
        Instant canceledAt,
        Instant createdAt) {

    /** The summary keeps the amount as text; it comes back exactly as stored. */
    public static SubscriberResponse from(SubscriberSummary summary) {
        return new SubscriberResponse(
                summary.id(),
                summary.name(),
                summary.email(),
                summary.status(),
                summary.planId(),
                summary.planPriceId(),
                new BigDecimal(summary.price()),
                summary.currency(),
                summary.interval(),
                summary.startedAt(),
                summary.nextBillingAt(),
                summary.canceledAt(),
                summary.createdAt());
    }
}
