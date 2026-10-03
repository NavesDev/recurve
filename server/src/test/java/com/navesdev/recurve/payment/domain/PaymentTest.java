package com.navesdev.recurve.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.navesdev.recurve.payment.domain.exception.PaymentAlreadyPaidException;
import com.navesdev.recurve.payment.domain.exception.PaymentAlreadySentException;
import com.navesdev.recurve.payment.domain.exception.PaymentNotPendingException;
import com.navesdev.recurve.payment.domain.exception.PaymentNotRefundableException;
import com.navesdev.recurve.payment.domain.exception.SubscriberNotBillableException;
import com.navesdev.recurve.plan.domain.BillingInterval;
import com.navesdev.recurve.plan.domain.Plan;
import com.navesdev.recurve.plan.domain.PlanPrice;
import com.navesdev.recurve.subscriber.domain.Subscriber;

/** The rules a charge obeys, stated as FR-04 and BR-05/BR-07/BR-08 state them. */
class PaymentTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    private static final Instant LATER = Instant.parse("2026-02-20T09:00:00Z");

    @Nested
    @DisplayName("FR-04.1 requesting a charge for a subscriber's cycle")
    class Charging {

        @Test
        void aChargeIsPendingAndDueWhenTheSubscriberIsNextBilled() {
            PlanPrice price = price("49.90");
            Subscriber subscriber = subscriber(price);

            Payment payment = Payment.charge(subscriber, price, NOW);

            assertThat(payment.getId()).isNotNull();
            assertThat(payment.getSubscriberId()).isEqualTo(subscriber.getId());
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(payment.getDueAt()).isEqualTo(subscriber.getNextBillingAt());
            assertThat(payment.getCreatedAt()).isEqualTo(NOW);
            assertThat(payment.getPaidAt()).isNull();
            assertThat(payment.isSent()).isFalse();
        }

        @Test
        void theAmountAndCurrencyAreASnapshotOfThePrice() {
            // BR-05: a later price change never alters a charge already made.
            PlanPrice price = price("49.90");

            Payment payment = Payment.charge(subscriber(price), price, NOW);

            assertThat(payment.getAmount()).isEqualByComparingTo("49.90");
            assertThat(payment.getCurrency()).isEqualTo("BRL");
        }

        @Test
        void aSubscriberIsChargedTheOnePriceItPays() {
            Subscriber subscriber = subscriber(price("49.90"));

            assertThatThrownBy(() -> Payment.charge(subscriber, price("59.90"), NOW))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void aCanceledSubscriberIsNeverCharged() {
            // BR-07.
            PlanPrice price = price("49.90");
            Subscriber subscriber = subscriber(price);
            subscriber.cancel(NOW);

            assertThatThrownBy(() -> Payment.charge(subscriber, price, NOW))
                    .isInstanceOf(SubscriberNotBillableException.class);
        }
    }

    @Nested
    @DisplayName("FR-04.7 a charge sent to the gateway")
    class Sending {

        @Test
        void theGatewaysIdAndInvoiceAreRecorded() {
            Payment payment = pending();

            payment.registerAtGateway("pay_080225913252", "https://sandbox.asaas.com/i/080225913252");

            assertThat(payment.isSent()).isTrue();
            assertThat(payment.getExternalId()).isEqualTo("pay_080225913252");
            assertThat(payment.getInvoiceUrl()).isEqualTo("https://sandbox.asaas.com/i/080225913252");
        }

        @Test
        void aChargeIsSentOnce() {
            Payment payment = pending();
            payment.registerAtGateway("pay_1", "https://sandbox.asaas.com/i/1");

            assertThatThrownBy(() -> payment.registerAtGateway("pay_2", "https://sandbox.asaas.com/i/2"))
                    .isInstanceOf(PaymentAlreadySentException.class);
            assertThat(payment.getExternalId()).isEqualTo("pay_1");
        }
    }

    @Nested
    @DisplayName("FR-04.2 / FR-04.5 confirming a payment")
    class Confirming {

        @Test
        void aPendingChargeBecomesPaidWithItsDate() {
            Payment payment = pending();

            payment.confirm(LATER);

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
            assertThat(payment.getPaidAt()).isEqualTo(LATER);
        }

        @Test
        void aFailedChargePaidLateBecomesPaid() {
            // The gateway accepts payment of an overdue charge.
            Payment payment = pending();
            payment.fail();

            payment.confirm(LATER);

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        }

        @Test
        void aPaymentIsNotPaidTwice() {
            Payment payment = pending();
            payment.confirm(LATER);

            assertThatThrownBy(() -> payment.confirm(LATER.plusSeconds(60)))
                    .isInstanceOf(PaymentAlreadyPaidException.class);
            assertThat(payment.getPaidAt()).isEqualTo(LATER);
        }

        @Test
        void aRefundedPaymentCannotBeConfirmed() {
            Payment payment = pending();
            payment.confirm(LATER);
            payment.refund(LATER);

            assertThatThrownBy(() -> payment.confirm(LATER)).isInstanceOf(PaymentNotPendingException.class);
        }
    }

    @Nested
    @DisplayName("FR-04.3 a charge that failed")
    class Failing {

        @Test
        void aPendingChargeBecomesFailed() {
            Payment payment = pending();

            payment.fail();

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        }

        @Test
        void onlyAPendingChargeCanFail() {
            Payment payment = pending();
            payment.confirm(LATER);

            assertThatThrownBy(payment::fail).isInstanceOf(PaymentNotPendingException.class);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        }
    }

    @Nested
    @DisplayName("FR-04.4 / BR-08 refunding a payment")
    class Refunding {

        @Test
        void aPaidPaymentIsRefundedWithItsDate() {
            Payment payment = pending();
            payment.confirm(NOW);

            payment.refund(LATER);

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
            assertThat(payment.getRefundedAt()).isEqualTo(LATER);
            assertThat(payment.getPaidAt()).isEqualTo(NOW);
        }

        @Test
        void onlyAPaidPaymentCanBeRefunded() {
            Payment payment = pending();

            assertThatThrownBy(() -> payment.refund(LATER)).isInstanceOf(PaymentNotRefundableException.class);
            payment.fail();
            assertThatThrownBy(() -> payment.refund(LATER)).isInstanceOf(PaymentNotRefundableException.class);
        }
    }

    @Nested
    @DisplayName("Asking before acting: whether a payment may move, without moving it")
    class Checks {

        @Test
        void aPendingOrFailedPaymentMayBeConfirmed() {
            Payment payment = pending();
            payment.requireConfirmable();
            payment.fail();
            payment.requireConfirmable();

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        }

        @Test
        void aPaidPaymentMayNotBeConfirmedAgain() {
            Payment payment = pending();
            payment.confirm(NOW);

            assertThatThrownBy(payment::requireConfirmable).isInstanceOf(PaymentAlreadyPaidException.class);
        }

        @Test
        void onlyAPaidPaymentMayBeRefunded() {
            Payment payment = pending();

            assertThatThrownBy(payment::requireRefundable).isInstanceOf(PaymentNotRefundableException.class);
            payment.confirm(NOW);
            payment.requireRefundable();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        }
    }

    private static Payment pending() {
        PlanPrice price = price("49.90");
        return Payment.charge(subscriber(price), price, NOW);
    }

    private static Subscriber subscriber(PlanPrice price) {
        return Subscriber.start("Grace", "grace@navy.mil", "52998224725", price, NOW);
    }

    private static PlanPrice price(String amount) {
        return Plan.create("Pro", null, NOW).addPrice(new BigDecimal(amount), "BRL", BillingInterval.MONTHLY, NOW);
    }
}
