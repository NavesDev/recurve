package com.navesdev.recurve.payment.gateway;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import com.navesdev.recurve.payment.domain.Payment;
import com.navesdev.recurve.subscriber.domain.Subscriber;

/**
 * The payment gateway, as the payment service sees it (FR-04.7). An
 * implementation translates whatever its vendor throws into
 * {@link com.navesdev.recurve.payment.domain.exception.PaymentGatewayException};
 * the service never sees a vendor's exception.
 */
public interface PaymentGateway {

    /** The subscriber's customer at the gateway: the one it already is, or a new one. */
    String ensureCustomer(Subscriber subscriber);

    /** Creates the charge, carrying the payment's id as the gateway's external reference. */
    Charge createCharge(Payment payment, String customerId);

    /** The charge created for this payment, if one reached the gateway — so a resend never charges twice. */
    Optional<Charge> findCharge(UUID paymentId);

    /** Where the charge stands at the gateway now: what a sync asks instead of waiting for the webhook. */
    ChargeState chargeState(String externalId);

    /** FR-04.5: the customer paid outside the gateway; it must stop charging. */
    void receiveInCash(String externalId, BigDecimal amount, LocalDate paidOn);

    /** FR-04.4: gives a paid charge back, in full. */
    void refund(String externalId);

    /** A charge at the gateway: its id there, and the page where the customer pays it. */
    record Charge(String externalId, String invoiceUrl) {
    }
}
