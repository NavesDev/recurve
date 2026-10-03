package com.navesdev.recurve.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.navesdev.recurve.plan.domain.BillingInterval;
import com.navesdev.recurve.plan.domain.Plan;
import com.navesdev.recurve.plan.domain.PlanPrice;
import com.navesdev.recurve.subscriber.domain.Subscriber;

/** What the listing shows of a payment (FR-04.6). */
class PaymentSummaryTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    private static final Instant LATER = Instant.parse("2026-02-20T10:00:00Z");

    @Test
    void everyFieldOfThePaymentIsShown() {
        PlanPrice price = Plan.create("Pro", null, NOW).addPrice(new BigDecimal("49.9"), "BRL", BillingInterval.MONTHLY, NOW);
        Subscriber subscriber = Subscriber.start("Grace", "grace@navy.mil", "52998224725", price, NOW);
        Payment payment = Payment.charge(subscriber, price, NOW);
        payment.registerAtGateway("pay_1", "https://sandbox.asaas.com/i/1");
        payment.confirm(LATER);
        payment.refund(LATER);

        PaymentSummary summary = PaymentSummary.of(payment);

        assertThat(summary.id()).isEqualTo(payment.getId());
        assertThat(summary.subscriberId()).isEqualTo(subscriber.getId());
        assertThat(summary.amount()).isEqualTo("49.90");
        assertThat(summary.currency()).isEqualTo("BRL");
        assertThat(summary.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(summary.dueAt()).isEqualTo(subscriber.getNextBillingAt());
        assertThat(summary.paidAt()).isEqualTo(LATER);
        assertThat(summary.refundedAt()).isEqualTo(LATER);
        assertThat(summary.externalId()).isEqualTo("pay_1");
        assertThat(summary.invoiceUrl()).isEqualTo("https://sandbox.asaas.com/i/1");
        assertThat(summary.createdAt()).isEqualTo(NOW);
    }
}
