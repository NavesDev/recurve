package com.navesdev.recurve.plan.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * How much and how often (FR-02.2). Part of the {@link Plan} aggregate and
 * changed only through it: creation and deactivation are package-private,
 * so BR-03 — one active price per cycle and currency — is the plan's to
 * guard. A price is never edited (BR-04); a new amount is a new price.
 */
@Entity
@Table(name = "plan_prices")
@Getter
public class PlanPrice {

    @Id
    private UUID id;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(nullable = false, length = 3)
    private String currency;

    /** {@code billing_interval}: {@code INTERVAL} is an SQL keyword. */
    @Enumerated(EnumType.STRING)
    @Column(name = "billing_interval", nullable = false, length = 10)
    private BillingInterval interval;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** For JPA only. */
    protected PlanPrice() {
    }

    static PlanPrice create(BigDecimal price, String currency, BillingInterval interval, Instant now) {
        PlanPrice created = new PlanPrice();
        created.id = UUID.randomUUID();
        created.price = PlanValidator.price(price);
        created.currency = PlanValidator.currency(currency);
        created.interval = PlanValidator.interval(interval);
        created.active = true;
        created.createdAt = Objects.requireNonNull(now, "now is required");
        return created;
    }

    /** BR-03: the pair this price occupies while it is in force. */
    boolean holds(BillingInterval interval, String currency) {
        return active && this.interval == interval && this.currency.equals(currency);
    }

    void deactivate() {
        active = false;
    }
}
