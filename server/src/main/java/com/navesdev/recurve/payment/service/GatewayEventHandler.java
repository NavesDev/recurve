package com.navesdev.recurve.payment.service;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.navesdev.recurve.payment.domain.Payment;
import com.navesdev.recurve.payment.domain.PaymentStatus;
import com.navesdev.recurve.payment.gateway.ChargeState;
import com.navesdev.recurve.subscriber.service.SubscriberService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * What the gateway's word does to a payment and its subscriber (FR-04.7),
 * whether it came as a webhook event or as the answer to a sync.
 * Idempotent — a gateway retries, and reports some payments twice — and
 * silent about charges Recurve does not hold. A late notice never undoes
 * a payment: an overdue notice for a paid charge changes nothing.
 *
 * <p>Runs inside the caller's transaction; never calls the gateway.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class GatewayEventHandler {

    private final PaymentStore store;
    private final SubscriberService subscriberService;
    private final Clock clock;

    void handle(GatewayEvent event) {
        Optional<Payment> found = paymentOf(event);
        if (found.isEmpty()) {
            log.warn("Gateway event {} for a charge Recurve does not hold: reference {}, id {}",
                    event.type(), event.externalReference(), event.externalId());
            return;
        }
        apply(found.get(), stateOf(event.type()));
    }

    /**
     * Brings the payment to where the charge stands at the gateway — the
     * one rule for the webhook and for a sync. A state already reached, or
     * one Recurve has no rule for, changes nothing.
     */
    void apply(Payment payment, ChargeState state) {
        switch (state) {
            case PAID -> paid(payment);
            case OVERDUE -> failed(payment);
            case REFUNDED -> refunded(payment);
            case PENDING, OTHER -> log.debug("Charge state {} changes nothing for payment {}", state, payment.getId());
        }
    }

    /** Asaas's webhook events onto the state each one reports. */
    private static ChargeState stateOf(String event) {
        return switch (event == null ? "" : event) {
            case "PAYMENT_CONFIRMED", "PAYMENT_RECEIVED" -> ChargeState.PAID;
            case "PAYMENT_OVERDUE", "PAYMENT_CREDIT_CARD_CAPTURE_REFUSED" -> ChargeState.OVERDUE;
            case "PAYMENT_REFUNDED" -> ChargeState.REFUNDED;
            default -> ChargeState.OTHER;
        };
    }

    /** FR-04.2; a failed charge may be paid late. */
    private void paid(Payment payment) {
        if (payment.getStatus() != PaymentStatus.PENDING && payment.getStatus() != PaymentStatus.FAILED) {
            return;
        }
        payment.confirm(clock.instant());
        store.persist(payment);
        subscriberService.confirmPayment(payment.getSubscriberId());
    }

    /** FR-04.3. */
    private void failed(Payment payment) {
        if (payment.getStatus() != PaymentStatus.PENDING) {
            return;
        }
        payment.fail();
        store.persist(payment);
        subscriberService.markPastDue(payment.getSubscriberId());
    }

    /** FR-04.4, refunded at the gateway's own console. */
    private void refunded(Payment payment) {
        if (payment.getStatus() != PaymentStatus.PAID) {
            return;
        }
        payment.refund(clock.instant());
        store.persist(payment);
    }

    /** By our id, which the gateway carries as its external reference; failing that, by the gateway's id. */
    private Optional<Payment> paymentOf(GatewayEvent event) {
        Optional<Payment> byReference = parse(event.externalReference()).flatMap(store::find);
        if (byReference.isPresent() || event.externalId() == null) {
            return byReference;
        }
        return store.findByExternalId(event.externalId());
    }

    private static Optional<UUID> parse(String reference) {
        if (reference == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(reference));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
