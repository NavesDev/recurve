package com.navesdev.recurve.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.navesdev.recurve.payment.domain.exception.PaymentAlreadyPaidException;
import com.navesdev.recurve.payment.domain.exception.PaymentAlreadySentException;
import com.navesdev.recurve.payment.domain.exception.PaymentNotPendingException;
import com.navesdev.recurve.payment.domain.exception.PaymentNotRefundableException;
import com.navesdev.recurve.payment.domain.exception.SubscriberNotBillableException;
import com.navesdev.recurve.plan.domain.PlanPrice;
import com.navesdev.recurve.subscriber.domain.Subscriber;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;

/**
 * The charge for one cycle of a subscriber (FR-04). Mutable entity, but
 * state changes only through a business method; time comes in as a
 * parameter. The subscriber is held by id: it is another aggregate.
 *
 * <p>Amount and currency are a snapshot of the price when the charge was
 * made (BR-05): the plan's price may change later without altering what
 * was already charged.
 */
@Entity
@Table(name = "payments")
@Getter
public class Payment {

    @Id
    private UUID id;

    @Column(name = "subscriber_id", nullable = false, updatable = false)
    private UUID subscriberId;

    @Column(nullable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PaymentStatus status;

    /** The cycle this charge pays: the subscriber's next billing date when it was requested. */
    @Column(name = "due_at", nullable = false, updatable = false)
    private Instant dueAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "refunded_at")
    private Instant refundedAt;

    /** The charge's id at the payment gateway; absent until it is sent. */
    @Column(name = "external_id", length = 64)
    private String externalId;

    /** Where the customer pays it — the gateway's invoice page. */
    @Column(name = "invoice_url", length = 500)
    private String invoiceUrl;

    /**
     * Optimistic lock: the gateway's webhook and an operator may change
     * the same charge at once. A wrapper, so that Spring Data reads
     * {@code null} as "new".
     */
    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** For JPA only. */
    protected Payment() {
    }

    /**
     * FR-04.1: the charge for the subscriber's current cycle, at the price
     * it pays. A canceled subscriber is never charged (BR-07), and one
     * without the tax document cannot be sent to the gateway.
     */
    public static Payment charge(Subscriber subscriber, PlanPrice price, Instant now) {
        Objects.requireNonNull(price, "price is required");
        if (!subscriber.isBillable()) {
            throw new SubscriberNotBillableException(subscriber.getId());
        }
        if (!price.getId().equals(subscriber.getPlanPriceId())) {
            throw new IllegalArgumentException(
                    "Price %s is not the one subscriber %s pays".formatted(price.getId(), subscriber.getId()));
        }

        Payment payment = new Payment();
        payment.id = UUID.randomUUID();
        payment.subscriberId = subscriber.getId();
        payment.amount = price.getPrice();
        payment.currency = price.getCurrency();
        payment.status = PaymentStatus.PENDING;
        payment.dueAt = subscriber.getNextBillingAt();
        payment.createdAt = Objects.requireNonNull(now, "now is required");
        return payment;
    }

    /** FR-04.7: the charge now exists at the gateway, once. */
    public void registerAtGateway(String externalId, String invoiceUrl) {
        if (isSent()) {
            throw new PaymentAlreadySentException(id);
        }
        this.externalId = Objects.requireNonNull(externalId, "externalId is required");
        this.invoiceUrl = invoiceUrl;
    }

    public boolean isSent() {
        return externalId != null;
    }

    /** FR-04.2, FR-04.5. A failed charge may still be paid late; a paid or refunded one may not be paid again. */
    public void confirm(Instant paidAt) {
        if (status == PaymentStatus.PAID) {
            throw new PaymentAlreadyPaidException(id);
        }
        if (status == PaymentStatus.REFUNDED) {
            throw new PaymentNotPendingException(id, status);
        }
        status = PaymentStatus.PAID;
        this.paidAt = Objects.requireNonNull(paidAt, "paidAt is required");
    }

    /** FR-04.3: declined, or due and unpaid. */
    public void fail() {
        if (status != PaymentStatus.PENDING) {
            throw new PaymentNotPendingException(id, status);
        }
        status = PaymentStatus.FAILED;
    }

    /** FR-04.4, BR-08: only what was paid can be given back. */
    public void refund(Instant now) {
        if (status != PaymentStatus.PAID) {
            throw new PaymentNotRefundableException(id, status);
        }
        status = PaymentStatus.REFUNDED;
        refundedAt = Objects.requireNonNull(now, "now is required");
    }
}
