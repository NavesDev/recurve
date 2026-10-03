package com.navesdev.recurve.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionOperations;

import com.navesdev.recurve.payment.domain.Payment;
import com.navesdev.recurve.payment.domain.PaymentStatus;
import com.navesdev.recurve.payment.domain.PaymentSummary;
import com.navesdev.recurve.payment.domain.exception.PaymentAlreadyPaidException;
import com.navesdev.recurve.payment.domain.exception.PaymentAlreadyRequestedException;
import com.navesdev.recurve.payment.domain.exception.PaymentGatewayException;
import com.navesdev.recurve.payment.domain.exception.PaymentNotFoundException;
import com.navesdev.recurve.payment.domain.exception.PaymentNotRefundableException;
import com.navesdev.recurve.payment.domain.exception.SubscriberNotBillableException;
import com.navesdev.recurve.payment.gateway.PaymentGateway;
import com.navesdev.recurve.payment.gateway.PaymentGateway.Charge;
import com.navesdev.recurve.payment.repository.PaymentRepository;
import com.navesdev.recurve.payment.repository.PaymentSearchRepository;
import com.navesdev.recurve.plan.domain.BillingInterval;
import com.navesdev.recurve.plan.domain.Plan;
import com.navesdev.recurve.plan.domain.PlanPrice;
import com.navesdev.recurve.plan.service.PlanService;
import com.navesdev.recurve.subscriber.domain.Subscriber;
import com.navesdev.recurve.subscriber.service.SubscriberService;

/**
 * The orchestration around a charge: what is checked, what the gateway is
 * asked and when, what happens to the subscriber, and that every write
 * reaches the index. Transactions are real only in {@code PaymentServiceIT}.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final Instant NOW = Instant.parse("2026-02-20T10:00:00Z");
    private static final Instant EARLIER = Instant.parse("2026-01-15T10:00:00Z");
    private static final Charge CHARGE = new Charge("pay_080225913252", "https://sandbox.asaas.com/i/080225913252");

    @Mock
    private PaymentRepository repository;

    @Mock
    private PaymentSearchRepository searchRepository;

    @Mock
    private SubscriberService subscriberService;

    @Mock
    private PlanService planService;

    @Mock
    private PaymentGateway gateway;

    private PaymentService service;

    private Plan plan;
    private PlanPrice price;
    private Subscriber subscriber;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        PaymentStore store = new PaymentStore(repository, searchRepository);
        service = new PaymentService(store, new GatewayEventHandler(store, subscriberService, clock),
                subscriberService, planService, gateway, TransactionOperations.withoutTransaction(), clock);
        plan = Plan.create("Pro", null, EARLIER);
        price = plan.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, EARLIER);
        subscriber = Subscriber.start("Grace", "grace@navy.mil", "52998224725", price, EARLIER);
    }

    @Nested
    @DisplayName("FR-04.1 an operator requests a charge")
    class Requesting {

        @Test
        void theChargeIsRecordedThenSentAndAnswersWithWhereToPay() {
            billable();
            remembersWhatItSaves();
            when(gateway.findCharge(any())).thenReturn(Optional.empty());
            when(gateway.ensureCustomer(subscriber)).thenReturn("cus_000005219613");
            when(gateway.createCharge(any(), any())).thenReturn(CHARGE);

            PaymentSummary requested = service.request(new RequestPaymentCommand(subscriber.getId()));

            assertThat(requested.status()).isEqualTo(PaymentStatus.PENDING);
            assertThat(requested.amount()).isEqualTo("49.90");
            assertThat(requested.dueAt()).isEqualTo(subscriber.getNextBillingAt());
            assertThat(requested.externalId()).isEqualTo("pay_080225913252");
            assertThat(requested.invoiceUrl()).isEqualTo("https://sandbox.asaas.com/i/080225913252");
            verify(gateway).createCharge(argThat(sent -> sent.getId().equals(requested.id())), any());
            verify(subscriberService).attachGatewayCustomer(subscriber.getId(), "cus_000005219613");
        }

        @Test
        void aCycleAlreadyChargedIsRefusedBeforeAnythingIsSent() {
            when(subscriberService.findForBilling(subscriber.getId())).thenReturn(subscriber);
            when(repository.existsBySubscriberIdAndDueAt(subscriber.getId(), subscriber.getNextBillingAt())).thenReturn(true);

            assertThatThrownBy(() -> service.request(new RequestPaymentCommand(subscriber.getId())))
                    .isInstanceOf(PaymentAlreadyRequestedException.class);

            verify(repository, never()).save(any());
            verifyNoInteractions(gateway);
        }

        @Test
        void aCanceledSubscriberIsNeverCharged() {
            subscriber.cancel(EARLIER);
            billable();

            assertThatThrownBy(() -> service.request(new RequestPaymentCommand(subscriber.getId())))
                    .isInstanceOf(SubscriberNotBillableException.class);

            verifyNoInteractions(gateway);
        }

        @Test
        void aGatewayFailureLeavesTheChargeRecordedAndUnsent() {
            // Recorded first, sent second: the operator retries the send.
            billable();
            remembersWhatItSaves();
            when(gateway.findCharge(any())).thenReturn(Optional.empty());
            when(gateway.ensureCustomer(subscriber)).thenThrow(new PaymentGatewayException("Asaas could not be reached", null));

            assertThatThrownBy(() -> service.request(new RequestPaymentCommand(subscriber.getId())))
                    .isInstanceOf(PaymentGatewayException.class);

            verify(repository).save(argThat(saved -> !saved.isSent()));
            verify(searchRepository).save(argThat(summary -> summary.externalId() == null));
        }
    }

    @Nested
    @DisplayName("FR-04.7 a charge is sent to the gateway once")
    class Sending {

        @Test
        void aChargeThatAlreadyReachedTheGatewayIsReusedNotCreatedAgain() {
            Payment payment = stored(pending());
            when(subscriberService.findForBilling(subscriber.getId())).thenReturn(subscriber);
            when(gateway.findCharge(payment.getId())).thenReturn(Optional.of(CHARGE));
            savesWhatItIsGiven();

            PaymentSummary sent = service.send(payment.getId());

            assertThat(sent.externalId()).isEqualTo("pay_080225913252");
            verify(gateway, never()).createCharge(any(), any());
            verify(gateway, never()).ensureCustomer(any());
        }

        @Test
        void aSentChargeIsReturnedAsItIs() {
            Payment payment = pending();
            payment.registerAtGateway("pay_1", null);
            stored(payment);

            assertThat(service.send(payment.getId()).externalId()).isEqualTo("pay_1");
            verifyNoInteractions(gateway);
        }

        @Test
        void aKnownCustomerIsNotAttachedAgain() {
            subscriber.attachGatewayCustomer("cus_000005219613");
            Payment payment = stored(pending());
            when(subscriberService.findForBilling(subscriber.getId())).thenReturn(subscriber);
            when(gateway.findCharge(payment.getId())).thenReturn(Optional.empty());
            when(gateway.ensureCustomer(subscriber)).thenReturn("cus_000005219613");
            when(gateway.createCharge(any(), any())).thenReturn(CHARGE);
            savesWhatItIsGiven();

            service.send(payment.getId());

            verify(subscriberService, never()).attachGatewayCustomer(any(), any());
        }
    }

    @Nested
    @DisplayName("FR-04.5 an operator confirms a payment received outside the gateway")
    class ConfirmingManually {

        @Test
        void theGatewayIsToldFirstThenThePaymentAndTheSubscriberMove() {
            Payment payment = pending();
            payment.registerAtGateway("pay_1", null);
            stored(payment);
            savesWhatItIsGiven();

            PaymentSummary confirmed = service.confirm(payment.getId());

            verify(gateway).receiveInCash("pay_1", new BigDecimal("49.90"), LocalDate.parse("2026-02-20"));
            assertThat(confirmed.status()).isEqualTo(PaymentStatus.PAID);
            assertThat(confirmed.paidAt()).isEqualTo(NOW);
            verify(subscriberService).confirmPayment(subscriber.getId());
        }

        @Test
        void anUnsentChargeIsConfirmedHereOnly() {
            Payment payment = stored(pending());
            savesWhatItIsGiven();

            service.confirm(payment.getId());

            verifyNoInteractions(gateway);
        }

        @Test
        void aGatewayFailureChangesNothingHere() {
            Payment payment = pending();
            payment.registerAtGateway("pay_1", null);
            stored(payment);
            doThrow(new PaymentGatewayException("refused", null))
                    .when(gateway).receiveInCash(any(), any(), any());

            assertThatThrownBy(() -> service.confirm(payment.getId())).isInstanceOf(PaymentGatewayException.class);

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
            verify(repository, never()).save(any());
            verifyNoInteractions(subscriberService);
        }

        @Test
        void aPaidPaymentIsRefusedBeforeTheGatewayIsAsked() {
            Payment payment = pending();
            payment.registerAtGateway("pay_1", null);
            payment.confirm(EARLIER);
            stored(payment);

            assertThatThrownBy(() -> service.confirm(payment.getId())).isInstanceOf(PaymentAlreadyPaidException.class);
            verifyNoInteractions(gateway);
        }
    }

    @Nested
    @DisplayName("FR-04.4 / BR-08 an operator refunds a payment")
    class Refunding {

        @Test
        void theGatewayGivesItBackThenThePaymentIsRefunded() {
            Payment payment = pending();
            payment.registerAtGateway("pay_1", null);
            payment.confirm(EARLIER);
            stored(payment);
            savesWhatItIsGiven();

            PaymentSummary refunded = service.refund(payment.getId());

            verify(gateway).refund("pay_1");
            assertThat(refunded.status()).isEqualTo(PaymentStatus.REFUNDED);
            assertThat(refunded.refundedAt()).isEqualTo(NOW);
            verifyNoInteractions(subscriberService);
        }

        @Test
        void anUnpaidPaymentIsRefusedBeforeTheGatewayIsAsked() {
            Payment payment = pending();
            payment.registerAtGateway("pay_1", null);
            stored(payment);

            assertThatThrownBy(() -> service.refund(payment.getId())).isInstanceOf(PaymentNotRefundableException.class);
            verifyNoInteractions(gateway);
        }
    }

    @Nested
    @DisplayName("FR-04.7 what the gateway tells us")
    class Webhook {

        @Test
        void aConfirmedChargePaysThePaymentAndMovesTheSubscriber() {
            Payment payment = sentAndFoundByReference();
            savesWhatItIsGiven();

            service.handle(event("PAYMENT_RECEIVED", payment));

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
            verify(subscriberService).confirmPayment(subscriber.getId());
            verifyNoInteractions(gateway);
        }

        @Test
        void aConfirmationHeardTwiceCountsOnce() {
            // Asaas sends both CONFIRMED and RECEIVED for a card payment.
            Payment payment = sentAndFoundByReference();
            payment.confirm(EARLIER);

            service.handle(event("PAYMENT_RECEIVED", payment));

            assertThat(payment.getPaidAt()).isEqualTo(EARLIER);
            verify(repository, never()).save(any());
            verifyNoInteractions(subscriberService);
        }

        @Test
        void anOverdueChargeFailsThePaymentAndTheSubscriberIsPastDue() {
            Payment payment = sentAndFoundByReference();
            savesWhatItIsGiven();

            service.handle(event("PAYMENT_OVERDUE", payment));

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
            verify(subscriberService).markPastDue(subscriber.getId());
        }

        @Test
        void aLateOverdueNoticeNeverUndoesAPayment() {
            Payment payment = sentAndFoundByReference();
            payment.confirm(EARLIER);

            service.handle(event("PAYMENT_OVERDUE", payment));

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
            verifyNoInteractions(subscriberService);
        }

        @Test
        void aRefundAtTheGatewayRefundsThePayment() {
            Payment payment = sentAndFoundByReference();
            payment.confirm(EARLIER);
            savesWhatItIsGiven();

            service.handle(event("PAYMENT_REFUNDED", payment));

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        }

        @Test
        void aPaymentIsFoundByTheGatewaysIdWhenTheReferenceIsMissing() {
            Payment payment = pending();
            payment.registerAtGateway("pay_1", null);
            when(repository.findByExternalId("pay_1")).thenReturn(Optional.of(payment));
            savesWhatItIsGiven();

            service.handle(new GatewayEvent("PAYMENT_RECEIVED", null, "pay_1"));

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        }

        @Test
        void aChargeRecurveDoesNotHoldIsIgnored() {
            when(repository.findByExternalId("pay_unknown")).thenReturn(Optional.empty());

            service.handle(new GatewayEvent("PAYMENT_RECEIVED", "not-a-uuid", "pay_unknown"));

            verify(repository, never()).save(any());
        }

        @Test
        void anEventOfNoInterestIsIgnored() {
            Payment payment = sentAndFoundByReference();

            service.handle(event("PAYMENT_BANK_SLIP_VIEWED", payment));

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
            verify(repository, never()).save(any());
        }

        private Payment sentAndFoundByReference() {
            Payment payment = pending();
            payment.registerAtGateway("pay_1", null);
            when(repository.findById(payment.getId())).thenReturn(Optional.of(payment));
            return payment;
        }

        private GatewayEvent event(String type, Payment payment) {
            return new GatewayEvent(type, payment.getId().toString(), payment.getExternalId());
        }
    }

    @Nested
    @DisplayName("What does not exist")
    class Missing {

        @Test
        void anUnknownPaymentIsNotFound() {
            UUID unknown = UUID.randomUUID();
            when(repository.findById(unknown)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.findById(unknown)).isInstanceOf(PaymentNotFoundException.class);
            assertThatThrownBy(() -> service.confirm(unknown)).isInstanceOf(PaymentNotFoundException.class);
            assertThatThrownBy(() -> service.refund(unknown)).isInstanceOf(PaymentNotFoundException.class);
            assertThatThrownBy(() -> service.send(unknown)).isInstanceOf(PaymentNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("FR-04.6 the index is rebuilt from the database")
    class Reindexing {

        @Test
        void theIndexIsRecreatedAndEveryPaymentIndexed() {
            when(repository.streamAll()).thenReturn(Stream.of(pending()));

            assertThat(service.reindex()).isEqualTo(1);
            verify(searchRepository).recreateIndex();
            verify(searchRepository).saveAll(argThat(batch -> batch.size() == 1));
            verify(searchRepository).refresh();
        }
    }

    private void billable() {
        when(subscriberService.findForBilling(subscriber.getId())).thenReturn(subscriber);
        when(planService.findByPriceIds(Set.of(price.getId()))).thenReturn(Map.of(price.getId(), plan));
    }

    private Payment pending() {
        return Payment.charge(subscriber, price, EARLIER);
    }

    private Payment stored(Payment payment) {
        when(repository.findById(payment.getId())).thenReturn(Optional.of(payment));
        return payment;
    }

    /** A repository that finds what it was given, as the real one would within the request. */
    private void remembersWhatItSaves() {
        Map<UUID, Payment> saved = new HashMap<>();
        when(repository.save(any(Payment.class))).thenAnswer(call -> {
            Payment payment = call.getArgument(0);
            saved.put(payment.getId(), payment);
            return payment;
        });
        when(repository.findById(any())).thenAnswer(call -> Optional.ofNullable(saved.get(call.<UUID>getArgument(0))));
    }

    private void savesWhatItIsGiven() {
        when(repository.save(any(Payment.class))).thenAnswer(call -> call.getArgument(0));
    }
}
