package com.navesdev.recurve.plan.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;

import com.navesdev.recurve.plan.domain.exception.PlanAlreadyInactiveException;
import com.navesdev.recurve.plan.domain.exception.PlanInactiveException;
import com.navesdev.recurve.plan.domain.exception.PriceAlreadyActiveException;
import com.navesdev.recurve.plan.domain.exception.PriceAlreadyInactiveException;
import com.navesdev.recurve.plan.domain.exception.PriceInactiveException;
import com.navesdev.recurve.plan.domain.exception.PriceNotFoundException;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;

/**
 * The product (FR-02): a name, and the prices it is sold at. The aggregate
 * root — every change to a price goes through here, because BR-03 is a
 * rule over the set of a plan's prices. Mutable entity, but state changes
 * only through a business method; time comes in as a parameter. Every
 * attribute goes through {@link PlanValidator} on the way in.
 */
@Entity
@Table(name = "plans")
@Getter
public class Plan {

    @Id
    private UUID id;

    @Column(nullable = false, length = PlanValidator.NAME_MAX_LENGTH)
    private String name;

    @Column(length = PlanValidator.DESCRIPTION_MAX_LENGTH)
    private String description;

    @Column(nullable = false)
    private boolean active;

    /**
     * Optimistic lock. A wrapper, so that Spring Data reads {@code null} as
     * "new" and persists rather than merges. Adding a price changes this
     * entity's own collection, so it increments the version too: two
     * requests pricing the same plan at once cannot both win.
     */
    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * Held by object, unlike a reference to another aggregate: a price is
     * part of this one. Eager, by a separate batched select rather than a
     * join — open-in-view is off and a response reads the prices after the
     * transaction. Nothing is deleted, so no orphan removal.
     */
    @OneToMany(cascade = { CascadeType.PERSIST, CascadeType.MERGE }, fetch = FetchType.EAGER)
    @JoinColumn(name = "plan_id", nullable = false, updatable = false)
    @Fetch(FetchMode.SELECT)
    @BatchSize(size = 100)
    @OrderBy("createdAt ASC")
    private List<PlanPrice> prices = new ArrayList<>();

    /** For JPA only. */
    protected Plan() {
    }

    public static Plan create(String name, String description, Instant now) {
        Plan plan = new Plan();
        plan.id = UUID.randomUUID();
        plan.name = PlanValidator.name(name);
        plan.description = PlanValidator.description(description);
        plan.active = true;
        plan.createdAt = Objects.requireNonNull(now, "now is required");
        return plan;
    }

    /** FR-02.6. A plan has no amount, so renaming it charges nobody differently. */
    public void update(String name, String description) {
        String validName = PlanValidator.name(name);
        String validDescription = PlanValidator.description(description);
        this.name = validName;
        this.description = validDescription;
    }

    /** FR-02.4: no new subscriber, no new price. The prices keep their own state. */
    public void deactivate() {
        if (!active) {
            throw new PlanAlreadyInactiveException(id);
        }
        active = false;
    }

    /** FR-02.2, guarded by BR-03. */
    public PlanPrice addPrice(BigDecimal price, String currency, BillingInterval interval, Instant now) {
        requireActive();
        PlanPrice created = PlanPrice.create(price, currency, interval, now);
        boolean taken = prices.stream().anyMatch(existing -> existing.holds(created.getInterval(), created.getCurrency()));
        if (taken) {
            throw new PriceAlreadyActiveException(created.getInterval(), created.getCurrency());
        }
        prices.add(created);
        return created;
    }

    /**
     * FR-02.7, BR-04: a new amount is a new price. The successor takes the
     * old one's cycle and currency; the old one stays on record, inactive,
     * and whoever subscribed to it keeps paying it.
     */
    public PlanPrice replacePrice(UUID priceId, BigDecimal price, Instant now) {
        requireActive();
        PlanPrice old = price(priceId);
        if (!old.isActive()) {
            throw new PriceInactiveException(priceId);
        }
        PlanPrice successor = PlanPrice.create(price, old.getCurrency(), old.getInterval(), now);
        old.deactivate();
        prices.add(successor);
        return successor;
    }

    /** FR-02.3: no new subscriber on it; existing ones stay. */
    public PlanPrice deactivatePrice(UUID priceId) {
        PlanPrice target = price(priceId);
        if (!target.isActive()) {
            throw new PriceAlreadyInactiveException(priceId);
        }
        target.deactivate();
        return target;
    }

    public PlanPrice price(UUID priceId) {
        return prices.stream()
                .filter(candidate -> candidate.getId().equals(priceId))
                .findFirst()
                .orElseThrow(() -> new PriceNotFoundException(priceId));
    }

    /** Unmodifiable, in creation order: the collection changes only through a business method. */
    public List<PlanPrice> getPrices() {
        return List.copyOf(prices);
    }

    private void requireActive() {
        if (!active) {
            throw new PlanInactiveException(id);
        }
    }
}
