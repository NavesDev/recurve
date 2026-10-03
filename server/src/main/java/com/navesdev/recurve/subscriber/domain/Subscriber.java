package com.navesdev.recurve.subscriber.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.navesdev.recurve.plan.domain.PlanPrice;
import com.navesdev.recurve.subscriber.domain.exception.SubscriberAlreadyCanceledException;
import com.navesdev.recurve.subscriber.domain.exception.SubscriberCanceledException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * A customer subscribed to a plan price (FR-03). Mutable entity, but state
 * changes only through a business method; time comes in as a parameter.
 * Every attribute goes through {@link SubscriberValidator} on the way in.
 *
 * <p>The price belongs to another aggregate, so it is held by id. It never
 * changes here: a new amount on the plan is a new price (BR-04), and this
 * subscriber stays on the one it chose.
 */
@Entity
@Table(name = "subscribers")
@Getter
public class Subscriber {

    @Id
    private UUID id;

    @Column(name = "plan_price_id", nullable = false, updatable = false)
    private UUID planPriceId;

    @Column(nullable = false, length = SubscriberValidator.NAME_MAX_LENGTH)
    private String name;

    @Column(nullable = false, length = SubscriberValidator.EMAIL_MAX_LENGTH)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SubscriberStatus status;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    /** BR-06: state, not a derivation of {@code startedAt}. Moves only by a domain event. */
    @Column(name = "next_billing_at", nullable = false)
    private Instant nextBillingAt;

    @Column(name = "canceled_at")
    private Instant canceledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** For JPA only. */
    protected Subscriber() {
    }

    /** FR-03.1, FR-03.2: active from now, first charge one cycle of the price ahead. */
    public static Subscriber start(String name, String email, PlanPrice price, Instant now) {
        Objects.requireNonNull(price, "price is required");
        Objects.requireNonNull(now, "now is required");

        Subscriber subscriber = new Subscriber();
        subscriber.id = UUID.randomUUID();
        subscriber.planPriceId = price.getId();
        subscriber.name = SubscriberValidator.name(name);
        subscriber.email = SubscriberValidator.email(email);
        subscriber.status = SubscriberStatus.ACTIVE;
        subscriber.startedAt = now;
        subscriber.nextBillingAt = price.getInterval().advance(now);
        subscriber.createdAt = now;
        return subscriber;
    }

    /** FR-03.7. Both attributes are validated before either changes. */
    public void update(String name, String email) {
        if (status == SubscriberStatus.CANCELED) {
            throw new SubscriberCanceledException(id);
        }
        String validName = SubscriberValidator.name(name);
        String validEmail = SubscriberValidator.email(email);
        this.name = validName;
        this.email = validEmail;
    }

    /** FR-03.3: no further charges (BR-07). */
    public void cancel(Instant now) {
        if (status == SubscriberStatus.CANCELED) {
            throw new SubscriberAlreadyCanceledException(id);
        }
        status = SubscriberStatus.CANCELED;
        canceledAt = Objects.requireNonNull(now, "now is required");
    }
}
