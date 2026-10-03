package com.navesdev.recurve.payment.gateway;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.navesdev.recurve.payment.domain.Payment;
import com.navesdev.recurve.subscriber.domain.Subscriber;

/**
 * A gateway that accepts everything and keeps what it was told in memory:
 * the default, so the application and its tests run with no gateway
 * account. Not a payment processor — nobody is ever charged.
 */
public class FakePaymentGateway implements PaymentGateway {

    private final Map<UUID, Charge> charges = new ConcurrentHashMap<>();

    @Override
    public String ensureCustomer(Subscriber subscriber) {
        return subscriber.getGatewayCustomerId() != null
                ? subscriber.getGatewayCustomerId()
                : "fake_cus_" + subscriber.getId();
    }

    @Override
    public Charge createCharge(Payment payment, String customerId) {
        Charge charge = new Charge("fake_pay_" + payment.getId(), "https://gateway.invalid/pay/" + payment.getId());
        charges.put(payment.getId(), charge);
        return charge;
    }

    @Override
    public Optional<Charge> findCharge(UUID paymentId) {
        return Optional.ofNullable(charges.get(paymentId));
    }

    /** Nobody pays a fake charge: it is awaiting payment for as long as it exists. */
    @Override
    public ChargeState chargeState(String externalId) {
        return ChargeState.PENDING;
    }

    @Override
    public void receiveInCash(String externalId, BigDecimal amount, LocalDate paidOn) {
        // Nothing to tell: nobody was being charged.
    }

    @Override
    public void refund(String externalId) {
        // Nothing to give back: nobody was charged.
    }
}
