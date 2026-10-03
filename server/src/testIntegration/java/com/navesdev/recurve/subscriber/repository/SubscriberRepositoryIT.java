package com.navesdev.recurve.subscriber.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import com.navesdev.recurve.plan.domain.BillingInterval;
import com.navesdev.recurve.plan.domain.Plan;
import com.navesdev.recurve.plan.domain.PlanPrice;
import com.navesdev.recurve.subscriber.domain.Subscriber;
import com.navesdev.recurve.subscriber.domain.SubscriberStatus;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;

/**
 * The subscriber against a real PostgreSQL, on the schema the migrations
 * produced: that it round-trips, that BR-02 can be asked and holds in the
 * schema too, and that the schema refuses what the domain never builds.
 * Each test rolls back.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SubscriberRepositoryIT {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    private static final Instant LATER = Instant.parse("2026-03-01T10:00:00Z");

    @Autowired
    private SubscriberRepository repository;

    @PersistenceContext
    private EntityManager entityManager;

    private PlanPrice price;

    @BeforeEach
    void setUp() {
        entityManager.createNativeQuery("TRUNCATE subscribers, plans CASCADE").executeUpdate();
        Plan plan = Plan.create("Pro", null, NOW);
        price = plan.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW);
        entityManager.persist(plan);
        entityManager.flush();
    }

    @Nested
    @DisplayName("A subscriber is stored as it was built")
    class RoundTrip {

        @Test
        void everyAttributeComesBack() {
            Subscriber subscriber = store(Subscriber.start("Grace", "grace@navy.mil", "52998224725", price, NOW));

            Subscriber found = repository.findById(subscriber.getId()).orElseThrow();

            assertThat(found.getPlanPriceId()).isEqualTo(price.getId());
            assertThat(found.getEmail()).isEqualTo("grace@navy.mil");
            assertThat(found.getDocument()).isEqualTo("52998224725");
            assertThat(found.getStatus()).isEqualTo(SubscriberStatus.ACTIVE);
            assertThat(found.getStartedAt()).isEqualTo(NOW);
            assertThat(found.getNextBillingAt()).isEqualTo(Instant.parse("2026-02-15T10:00:00Z"));
            assertThat(found.getCanceledAt()).isNull();
        }

        @Test
        void theGatewayCustomerIsStored() {
            Subscriber subscriber = store(Subscriber.start("Grace", "grace@navy.mil", "52998224725", price, NOW));

            Subscriber loaded = repository.findById(subscriber.getId()).orElseThrow();
            loaded.attachGatewayCustomer("cus_000005219613");
            store(loaded);

            assertThat(repository.findById(subscriber.getId()).orElseThrow().getGatewayCustomerId())
                    .isEqualTo("cus_000005219613");
        }

        @Test
        void aSubscriberFromBeforeTheDocumentLoadsAndIsNotBillable() {
            Subscriber subscriber = store(Subscriber.start("Grace", "grace@navy.mil", "52998224725", price, NOW));
            entityManager.createNativeQuery("UPDATE subscribers SET document = NULL").executeUpdate();

            assertThat(repository.findById(subscriber.getId()).orElseThrow().isBillable()).isFalse();
        }

        @Test
        void aCancellationIsStored() {
            Subscriber subscriber = store(Subscriber.start("Grace", "grace@navy.mil", "52998224725", price, NOW));

            Subscriber loaded = repository.findById(subscriber.getId()).orElseThrow();
            loaded.cancel(LATER);
            store(loaded);

            Subscriber found = repository.findById(subscriber.getId()).orElseThrow();
            assertThat(found.getStatus()).isEqualTo(SubscriberStatus.CANCELED);
            assertThat(found.getCanceledAt()).isEqualTo(LATER);
        }
    }

    @Nested
    @DisplayName("BR-02 the subscriber email is unique")
    class UniqueEmail {

        @Test
        void anEmailInUseIsFound() {
            store(Subscriber.start("Grace", "grace@navy.mil", "52998224725", price, NOW));

            assertThat(repository.existsByEmail("grace@navy.mil")).isTrue();
            assertThat(repository.existsByEmail("ada@navy.mil")).isFalse();
        }

        @Test
        void aSubscriberDoesNotCollideWithItself() {
            Subscriber grace = store(Subscriber.start("Grace", "grace@navy.mil", "52998224725", price, NOW));
            Subscriber ada = store(Subscriber.start("Ada", "ada@navy.mil", "52998224725", price, NOW));

            assertThat(repository.existsByEmailAndIdNot("grace@navy.mil", grace.getId())).isFalse();
            assertThat(repository.existsByEmailAndIdNot("grace@navy.mil", ada.getId())).isTrue();
        }

        @Test
        void aCanceledSubscriberKeepsTheEmail() {
            Subscriber grace = Subscriber.start("Grace", "grace@navy.mil", "52998224725", price, NOW);
            grace.cancel(LATER);
            store(grace);

            assertThat(repository.existsByEmail("grace@navy.mil")).isTrue();
        }

        @Test
        void twoSubscribersWithOneEmailCannotBeStored() {
            // The service checks first; the constraint stands behind it for
            // two requests that race past that check.
            store(Subscriber.start("Grace", "grace@navy.mil", "52998224725", price, NOW));

            assertThatThrownBy(() -> store(Subscriber.start("Grace", "grace@navy.mil", "52998224725", price, NOW)))
                    .isInstanceOf(PersistenceException.class);
        }
    }

    @Nested
    @DisplayName("The schema refuses what the domain never builds")
    class Schema {

        @Test
        void aSubscriberOnAPriceThatDoesNotExistCannotBeStored() {
            assertThatThrownBy(() -> insert(UUID.randomUUID(), "ACTIVE", null)).isInstanceOf(PersistenceException.class);
        }

        @Test
        void aCanceledSubscriberWithoutACancellationDateCannotBeStored() {
            assertThatThrownBy(() -> insert(price.getId(), "CANCELED", null)).isInstanceOf(PersistenceException.class);
        }

        @Test
        void anActiveSubscriberWithACancellationDateCannotBeStored() {
            assertThatThrownBy(() -> insert(price.getId(), "ACTIVE", LATER)).isInstanceOf(PersistenceException.class);
        }

        private void insert(UUID priceId, String status, Instant canceledAt) {
            entityManager.createNativeQuery("""
                    INSERT INTO subscribers (id, plan_price_id, name, email, status, started_at,
                                             next_billing_at, canceled_at, created_at)
                    VALUES (gen_random_uuid(), :price, 'Grace', 'grace@navy.mil', :status, now(),
                            now(), :canceledAt, now())
                    """)
                    .setParameter("price", priceId)
                    .setParameter("status", status)
                    .setParameter("canceledAt", canceledAt)
                    .executeUpdate();
        }
    }

    @Nested
    @DisplayName("FR-03.4 every subscriber, for rebuilding the index")
    class Streaming {

        @Test
        void everySubscriberIsStreamed() {
            store(Subscriber.start("Grace", "grace@navy.mil", "52998224725", price, NOW));
            store(Subscriber.start("Ada", "ada@navy.mil", "52998224725", price, NOW));

            try (Stream<Subscriber> subscribers = repository.streamAll()) {
                assertThat(subscribers).extracting(Subscriber::getName).containsExactlyInAnyOrder("Grace", "Ada");
            }
        }
    }

    private Subscriber store(Subscriber subscriber) {
        Subscriber saved = repository.save(subscriber);
        entityManager.flush();
        entityManager.clear();
        return saved;
    }
}
