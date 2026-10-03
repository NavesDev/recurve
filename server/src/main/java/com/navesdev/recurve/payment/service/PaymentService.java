package com.navesdev.recurve.payment.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import com.navesdev.recurve.payment.domain.Payment;
import com.navesdev.recurve.payment.domain.PaymentSummary;
import com.navesdev.recurve.payment.domain.exception.PaymentAlreadyRequestedException;
import com.navesdev.recurve.payment.domain.exception.SubscriberNotBillableException;
import com.navesdev.recurve.payment.gateway.PaymentGateway;
import com.navesdev.recurve.payment.gateway.PaymentGateway.Charge;
import com.navesdev.recurve.plan.domain.PlanPrice;
import com.navesdev.recurve.plan.service.PlanService;
import com.navesdev.recurve.shared.service.SearchFilter;
import com.navesdev.recurve.subscriber.domain.Subscriber;
import com.navesdev.recurve.subscriber.service.SubscriberService;

import lombok.RequiredArgsConstructor;

/**
 * The feature's single entry point. One method per use case.
 *
 * <p>A call to the payment gateway is never made inside a database
 * transaction: it cannot be rolled back, and it would hold a pooled
 * connection for as long as the gateway takes. So a use case that talks to
 * the gateway runs in steps — record or check here, call the gateway, then
 * record what it answered — each database step its own transaction. A
 * charge is recorded before it is sent and carries its own id to the
 * gateway, so a send that fails halfway is retried without charging twice.
 *
 * <p>Within each transaction, every write reaches the database and the
 * index together ({@link PaymentStore}); a failure to index rolls it back.
 */
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentStore store;
    private final GatewayEventHandler events;
    private final SubscriberService subscriberService;
    private final PlanService planService;
    private final PaymentGateway gateway;
    private final TransactionOperations transaction;
    private final Clock clock;

    /**
     * FR-04.1: records the charge for the subscriber's current cycle, then
     * sends it. If the gateway fails, the charge stays recorded and unsent,
     * the caller gets the gateway's error, and {@link #send} retries.
     */
    public PaymentSummary request(RequestPaymentCommand command) {
        UUID paymentId = transaction.execute(status -> {
            Subscriber subscriber = subscriberService.findForBilling(command.subscriberId());
            if (store.isCharged(subscriber.getId(), subscriber.getNextBillingAt())) {
                throw new PaymentAlreadyRequestedException(subscriber.getId(), subscriber.getNextBillingAt());
            }
            return store.persist(Payment.charge(subscriber, priceOf(subscriber), clock.instant())).id();
        });
        return send(paymentId);
    }

    /** FR-04.7: sends a recorded charge to the gateway, once; a sent one is returned as it is. */
    public PaymentSummary send(UUID paymentId) {
        Payment payment = store.findOrThrow(paymentId);
        if (payment.isSent()) {
            return PaymentSummary.of(payment);
        }
        Subscriber subscriber = subscriberService.findForBilling(payment.getSubscriberId());
        if (!subscriber.isBillable()) {
            throw new SubscriberNotBillableException(subscriber.getId());
        }

        Charge charge = gateway.findCharge(paymentId).orElseGet(() -> createCharge(payment, subscriber));

        return transaction.execute(status -> {
            Payment current = store.findOrThrow(paymentId);
            current.registerAtGateway(charge.externalId(), charge.invoiceUrl());
            return store.persist(current);
        });
    }

    /**
     * FR-04.5: the customer paid outside the gateway. The gateway is told
     * first, so it stops charging; if it refuses, nothing changes here.
     */
    public PaymentSummary confirm(UUID paymentId) {
        Payment payment = store.findOrThrow(paymentId);
        payment.requireConfirmable();
        Instant now = clock.instant();
        if (payment.isSent()) {
            gateway.receiveInCash(payment.getExternalId(), payment.getAmount(), LocalDate.ofInstant(now, ZoneOffset.UTC));
        }
        return transaction.execute(status -> {
            Payment current = store.findOrThrow(paymentId);
            current.confirm(now);
            PaymentSummary summary = store.persist(current);
            subscriberService.confirmPayment(current.getSubscriberId());
            return summary;
        });
    }

    /** FR-04.4, BR-08: the gateway gives it back first; then it is refunded here. */
    public PaymentSummary refund(UUID paymentId) {
        Payment payment = store.findOrThrow(paymentId);
        payment.requireRefundable();
        if (payment.isSent()) {
            gateway.refund(payment.getExternalId());
        }
        return transaction.execute(status -> {
            Payment current = store.findOrThrow(paymentId);
            current.refund(clock.instant());
            return store.persist(current);
        });
    }

    /** FR-04.7: what the gateway reports about one of its charges, in one transaction. */
    public void handle(GatewayEvent event) {
        transaction.executeWithoutResult(status -> events.handle(event));
    }

    public PaymentSummary findById(UUID paymentId) {
        return PaymentSummary.of(store.findOrThrow(paymentId));
    }

    /** FR-04.6 and FR-07: search and filter, then sort, then paginate — all in the index. */
    public Page<PaymentSummary> search(SearchFilter filter, Pageable pageable) {
        return store.search(filter, pageable);
    }

    /** Rebuilds the index from the database and returns how many payments it holds. */
    public long reindex() {
        return transaction.execute(status -> store.reindex());
    }

    private Charge createCharge(Payment payment, Subscriber subscriber) {
        String customerId = gateway.ensureCustomer(subscriber);
        if (subscriber.getGatewayCustomerId() == null) {
            subscriberService.attachGatewayCustomer(subscriber.getId(), customerId);
        }
        return gateway.createCharge(payment, customerId);
    }

    /** The price the subscriber pays, whether or not it is still on sale: an existing subscriber keeps it. */
    private PlanPrice priceOf(Subscriber subscriber) {
        UUID priceId = subscriber.getPlanPriceId();
        return planService.findByPriceIds(Set.of(priceId)).get(priceId).price(priceId);
    }
}
