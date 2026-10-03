package com.navesdev.recurve.plan.controller;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.navesdev.recurve.plan.domain.BillingInterval;
import com.navesdev.recurve.plan.domain.PlanPrice;
import com.navesdev.recurve.plan.domain.PlanSummary;

/** An amount goes out as a JSON number with its two decimal places (NFR-06). */
public record PriceResponse(
        UUID id,
        BigDecimal price,
        String currency,
        BillingInterval interval,
        boolean active,
        Instant createdAt) {

    public static PriceResponse from(PlanPrice price) {
        return new PriceResponse(
                price.getId(),
                price.getPrice(),
                price.getCurrency(),
                price.getInterval(),
                price.isActive(),
                price.getCreatedAt());
    }

    /** The index keeps the amount as text; it comes back exactly as stored. */
    public static PriceResponse from(PlanSummary.Price price) {
        return new PriceResponse(
                price.id(),
                new BigDecimal(price.price()),
                price.currency(),
                price.interval(),
                price.active(),
                price.createdAt());
    }
}
