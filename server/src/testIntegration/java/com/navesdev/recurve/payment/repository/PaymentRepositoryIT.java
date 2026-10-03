package com.navesdev.recurve.payment.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import com.navesdev.recurve.payment.domain.Payment;
import com.navesdev.recurve.payment.domain.PaymentStatus;
import com.navesdev.recurve.plan.domain.BillingInterval;
import com.navesdev.recurve.plan.domain.Plan;
import com.navesdev.recurve.plan.domain.PlanPrice;
import com.navesdev.recurve.subscriber.domain.Subscriber;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;

/**
 * The charge against a real PostgreSQL, on the schema the migrations
 * produced: that it round-trips, that one cycle is charged once and one
 * gateway charge is one payment, and that the schema refuses what the
 * domain never builds. Each test rolls back.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PaymentRepositoryIT {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    private static final Instant LATER = Instant.parse("2026-02-20T10:00:00Z");

    @Autowired
    private PaymentRepository repository;

    @PersistenceContext
    private EntityManager entityManager;

    private PlanPrice price;
    private Subscriber subscriber;

    @BeforeEach
    void setUp() {
        entityManager.createNativeQuery("TRUNCATE payments, subscribers, plans CASCADE").executeUpdate();
        Plan plan = Plan.create("Pro", null, NOW);
        price = plan.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW);
        entityManager.persist(plan);
        subscriber = Subscriber.start("Grace", "grace@navy.mil", "52998224725", price, NOW);
        entityManager.persist(subscriber);
        entityManager.flush();
    }

    @Nested
    @DisplayName("A charge is stored as it was made")
    class RoundTrip {

        @Test
        void everyAttributeComesBack() {
            Payment payment = Payment.charge(subscriber, price, NOW);
            payment.registerAtGateway("pay_080225913252", "https://sandbox.asaas.com/i/080225913252");
            payment.confirm(LATER);
            store(payment);

            Payment found = repository.findById(payment.getId()).orElseThrow();

            assertThat(found.getSubscriberId()).isEqualTo(subscriber.getId());
            assertThat(found.getAmount()).isEqualByComparingTo("49.90");
            assertThat(found.getCurrency()).isEqualTo("BRL");
            assertThat(found.getStatus()).isEqualTo(PaymentStatus.PAID);
            assertThat(found.getDueAt()).isEqualTo(subscriber.getNextBillingAt());
            assertThat(found.getPaidAt()).isEqualTo(LATER);
            assertThat(found.getExternalId()).isEqualTo("pay_080225913252");
            assertThat(found.getInvoiceUrl()).isEqualTo("https://sandbox.asaas.com/i/080225913252");
            assertThat(found.getVersion()).isNotNull();
        }
    }

    @Nested
    @DisplayName("One charge per cycle, one payment per gateway charge")
    class Uniqueness {

        @Test
        void aChargedCycleIsFound() {
            store(Payment.charge(subscriber, price, NOW));

            assertThat(repository.existsBySubscriberIdAndDueAt(subscriber.getId(), subscriber.getNextBillingAt())).isTrue();
            assertThat(repository.existsBySubscriberIdAndDueAt(subscriber.getId(), LATER)).isFalse();
        }

        @Test
        void aCycleCannotBeChargedTwice() {
            // The service checks first; the constraint stands behind it for
            // two requests that race past that check.
            store(Payment.charge(subscriber, price, NOW));

            assertThatThrownBy(() -> store(Payment.charge(subscriber, price, NOW)))
                    .isInstanceOf(PersistenceException.class);
        }

        @Test
        void aGatewayChargeIsFoundByItsId() {
            Payment payment = Payment.charge(subscriber, price, NOW);
            payment.registerAtGateway("pay_1", null);
            store(payment);

            assertThat(repository.findByExternalId("pay_1")).get().extracting(Payment::getId).isEqualTo(payment.getId());
            assertThat(repository.findByExternalId("pay_2")).isEmpty();
        }
    }

    @Nested
    @DisplayName("The schema refuses what the domain never builds")
    class Schema {

        @Test
        void aPaidPaymentWithoutAPaymentDateCannotBeStored() {
            assertThatThrownBy(() -> insert("PAID", null, null)).isInstanceOf(PersistenceException.class);
        }

        @Test
        void aRefundedPaymentWithoutARefundDateCannotBeStored() {
            assertThatThrownBy(() -> insert("REFUNDED", LATER, null)).isInstanceOf(PersistenceException.class);
        }

        @Test
        void aPendingPaymentWithAPaymentDateCannotBeStored() {
            assertThatThrownBy(() -> insert("PENDING", LATER, null)).isInstanceOf(PersistenceException.class);
        }

        private void insert(String status, Instant paidAt, Instant refundedAt) {
            entityManager.createNativeQuery("""
                    INSERT INTO payments (id, subscriber_id, amount, currency, status, due_at, paid_at,
                                          refunded_at, version, created_at)
                    VALUES (gen_random_uuid(), :subscriber, 49.90, 'BRL', :status, now(), :paidAt,
                            :refundedAt, 0, now())
                    """)
                    .setParameter("subscriber", subscriber.getId())
                    .setParameter("status", status)
                    .setParameter("paidAt", paidAt)
                    .setParameter("refundedAt", refundedAt)
                    .executeUpdate();
        }
    }

    @Nested
    @DisplayName("FR-04.6 every payment, for rebuilding the index")
    class Streaming {

        @Test
        void everyPaymentIsStreamed() {
            store(Payment.charge(subscriber, price, NOW));

            try (Stream<Payment> payments = repository.streamAll()) {
                assertThat(payments).hasSize(1);
            }
        }
    }

    private Payment store(Payment payment) {
        Payment saved = repository.save(payment);
        entityManager.flush();
        entityManager.clear();
        return saved;
    }
}
