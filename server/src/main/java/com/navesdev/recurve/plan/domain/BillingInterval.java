package com.navesdev.recurve.plan.domain;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * How often a price is charged (FR-02.2). Knows how far one cycle
 * reaches, which is what moves a subscriber's next billing date
 * (FR-03.2, FR-04.2). Counted on the UTC calendar (NFR-05); a month too
 * short for the starting day ends on its last day.
 */
public enum BillingInterval {

    MONTHLY,
    YEARLY;

    public Instant advance(Instant from) {
        OffsetDateTime utc = from.atOffset(ZoneOffset.UTC);
        return switch (this) {
            case MONTHLY -> utc.plusMonths(1).toInstant();
            case YEARLY -> utc.plusYears(1).toInstant();
        };
    }
}
