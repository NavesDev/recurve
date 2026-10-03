package com.navesdev.recurve.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.navesdev.recurve.payment.domain.PaymentStatus;
import com.navesdev.recurve.payment.domain.PaymentSummary;
import com.navesdev.recurve.payment.repository.PaymentRepository;
import com.navesdev.recurve.payment.repository.PaymentSearchRepository;
import com.navesdev.recurve.plan.domain.BillingInterval;
import com.navesdev.recurve.plan.domain.Plan;
import com.navesdev.recurve.plan.domain.PlanPrice;
import com.navesdev.recurve.plan.repository.PlanRepository;
import com.navesdev.recurve.subscriber.domain.Subscriber;
import com.navesdev.recurve.subscriber.domain.SubscriberStatus;
import com.navesdev.recurve.subscriber.repository.SubscriberRepository;
import com.navesdev.recurve.subscriber.repository.SubscriberSearchRepository;

/**
 * What only real commits show, with the default fake gateway: a request
 * records the charge and then sends it, a confirmation commits the payment
 * and the subscriber together, and a write the index refuses leaves
 * nothing behind. Not {@code @Transactional}; the tables are truncated
 * before each test, and both indexes are mocked.
 */
@SpringBootTest
class PaymentServiceIT {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    @Autowired
    private PaymentService service;

    @Autowired
    private PaymentRepository repository;

    @Autowired
    private SubscriberRepository subscriberRepository;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private PaymentSearchRepository searchRepository;

    @MockitoBean
    private SubscriberSearchRepository subscriberSearchRepository;

    private Subscriber subscriber;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE payments, subscribers, plans CASCADE");
        Plan plan = Plan.create("Pro", null, NOW);
        PlanPrice price = plan.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW);
        planRepository.save(plan);
        subscriber = subscriberRepository.save(Subscriber.start("Grace", "grace@navy.mil", "52998224725", price, NOW));
    }

    @Nested
    @DisplayName("FR-04.1 a requested charge, committed")
    class Requesting {

        @Test
        void theChargeIsStoredSentAndTheSubscriberIsACustomer() {
            PaymentSummary requested = service.request(new RequestPaymentCommand(subscriber.getId()));

            assertThat(requested.externalId()).isNotNull();
            assertThat(repository.findById(requested.id()).orElseThrow().isSent()).isTrue();
            assertThat(subscriberRepository.findById(subscriber.getId()).orElseThrow().getGatewayCustomerId()).isNotNull();
        }

        @Test
        void aWriteTheIndexRefusesLeavesNothingInTheDatabase() {
            doThrow(new DataAccessResourceFailureException("search node down"))
                    .when(searchRepository).save(any(PaymentSummary.class));

            assertThatThrownBy(() -> service.request(new RequestPaymentCommand(subscriber.getId())))
                    .isInstanceOf(DataAccessResourceFailureException.class);

            assertThat(repository.count()).isZero();
        }
    }

    @Nested
    @DisplayName("FR-04.2 a confirmation moves payment and subscriber together")
    class Confirming {

        @Test
        void bothAreCommitted() {
            UUID paymentId = service.request(new RequestPaymentCommand(subscriber.getId())).id();
            Instant nextBilling = subscriber.getNextBillingAt();

            service.confirm(paymentId);

            assertThat(repository.findById(paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PAID);
            Subscriber stored = subscriberRepository.findById(subscriber.getId()).orElseThrow();
            assertThat(stored.getNextBillingAt()).isEqualTo(BillingInterval.MONTHLY.advance(nextBilling));
            assertThat(stored.getStatus()).isEqualTo(SubscriberStatus.ACTIVE);
        }

        @Test
        void aSubscriberThatCouldNotBeIndexedLeavesThePaymentUnpaid() {
            UUID paymentId = service.request(new RequestPaymentCommand(subscriber.getId())).id();
            doThrow(new DataAccessResourceFailureException("search node down"))
                    .when(subscriberSearchRepository).save(any());

            assertThatThrownBy(() -> service.confirm(paymentId)).isInstanceOf(DataAccessResourceFailureException.class);

            assertThat(repository.findById(paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PENDING);
        }
    }

    @Nested
    @DisplayName("FR-04.7 the gateway's word, committed")
    class Webhook {

        @Test
        void anOverdueChargeLeavesThePaymentFailedAndTheSubscriberPastDue() {
            PaymentSummary requested = service.request(new RequestPaymentCommand(subscriber.getId()));

            service.handle(new GatewayEvent("PAYMENT_OVERDUE", requested.id().toString(), requested.externalId()));

            assertThat(repository.findById(requested.id()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(subscriberRepository.findById(subscriber.getId()).orElseThrow().getStatus())
                    .isEqualTo(SubscriberStatus.PAST_DUE);
        }
    }
}
