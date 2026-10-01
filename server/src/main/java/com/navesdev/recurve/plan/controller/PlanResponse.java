package com.navesdev.recurve.plan.controller;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.navesdev.recurve.plan.domain.Plan;
import com.navesdev.recurve.plan.domain.PlanSummary;

/**
 * A plan with every price it has had, inactive ones included: the history
 * is the point of never editing a price (BR-04). Reads getters only.
 */
public record PlanResponse(
        UUID id,
        String name,
        String description,
        boolean active,
        Instant createdAt,
        List<PriceResponse> prices) {

    public static PlanResponse from(Plan plan) {
        return new PlanResponse(
                plan.getId(),
                plan.getName(),
                plan.getDescription(),
                plan.isActive(),
                plan.getCreatedAt(),
                plan.getPrices().stream().map(PriceResponse::from).toList());
    }

    /** The listing (FR-06.2) answers from the read model, not the entity. */
    public static PlanResponse from(PlanSummary summary) {
        return new PlanResponse(
                summary.id(),
                summary.name(),
                summary.description(),
                summary.active(),
                summary.createdAt(),
                summary.prices().stream().map(PriceResponse::from).toList());
    }
}
