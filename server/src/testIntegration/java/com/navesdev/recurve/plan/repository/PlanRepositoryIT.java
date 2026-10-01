package com.navesdev.recurve.plan.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;

/**
 * The plan aggregate against a real PostgreSQL, on the schema the
 * migrations produced: that it round-trips with its prices, that a price
 * leads to its plan, and that BR-03 holds in the schema too. Each test
 * rolls back.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PlanRepositoryIT {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    private static final Instant LATER = Instant.parse("2026-03-01T10:00:00Z");

    @Autowired
    private PlanRepository repository;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        entityManager.createNativeQuery("TRUNCATE plans CASCADE").executeUpdate();
    }

    @Nested
    @DisplayName("A plan is stored with its prices")
    class RoundTrip {

        @Test
        void thePricesComeBackWithThePlanInTheOrderTheyWereCreated() {
            Plan plan = Plan.create("Pro", "For teams", NOW);
            PlanPrice monthly = plan.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW);
            PlanPrice yearly = plan.addPrice(new BigDecimal("499.00"), "BRL", BillingInterval.YEARLY, LATER);
            store(plan);

            Plan found = repository.findById(plan.getId()).orElseThrow();

            assertThat(found.getPrices()).extracting(PlanPrice::getId).containsExactly(monthly.getId(), yearly.getId());
            assertThat(found.getPrices().getFirst().getPrice()).isEqualByComparingTo("49.90");
            assertThat(found.getPrices().getFirst().getInterval()).isEqualTo(BillingInterval.MONTHLY);
        }

        @Test
        void aPriceAddedToAStoredPlanIsStoredWithIt() {
            Plan plan = store(Plan.create("Pro", null, NOW));

            Plan loaded = repository.findById(plan.getId()).orElseThrow();
            loaded.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW);
            repository.save(loaded);
            entityManager.flush();
            entityManager.clear();

            assertThat(repository.findById(plan.getId()).orElseThrow().getPrices()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("A price is reached through its plan")
    class ByPrice {

        @Test
        void thePlanHoldingAPriceIsFoundByThatPrice() {
            Plan plan = Plan.create("Pro", null, NOW);
            PlanPrice price = plan.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW);
            store(plan);
            store(Plan.create("Basic", null, NOW));

            assertThat(repository.findByPriceId(price.getId())).get()
                    .extracting(Plan::getId).isEqualTo(plan.getId());
        }

        @Test
        void aPriceNobodyHoldsFindsNoPlan() {
            assertThat(repository.findByPriceId(UUID.randomUUID())).isEmpty();
        }
    }

    @Nested
    @DisplayName("FR-02.5 every plan, for rebuilding the index")
    class Streaming {

        @Test
        void everyPlanIsStreamed() {
            store(Plan.create("Pro", null, NOW));
            store(Plan.create("Basic", null, NOW));

            try (Stream<Plan> plans = repository.streamAll()) {
                assertThat(plans).extracting(Plan::getName).containsExactlyInAnyOrder("Pro", "Basic");
            }
        }
    }

    @Nested
    @DisplayName("BR-03 one active price per cycle and currency, in the schema too")
    class OneActivePrice {

        @Test
        void twoActivePricesOnOnePairCannotBeCommitted() {
            // The domain refuses this first; the constraint stands behind
            // it for two requests that race past the domain check. It is
            // deferred to commit, so the test asks for the check at once.
            Plan plan = store(Plan.create("Pro", null, NOW));
            insertActivePrice(plan.getId());
            entityManager.createNativeQuery("SET CONSTRAINTS ALL IMMEDIATE").executeUpdate();

            assertThatThrownBy(() -> insertActivePrice(plan.getId())).isInstanceOf(PersistenceException.class);
        }

        @Test
        void aReplacementIsNotTwoActivePrices() {
            // Hibernate flushes the successor's insert before the old
            // price's update; deferring the check to commit is what lets
            // a replace (BR-04) through.
            Plan plan = Plan.create("Pro", null, NOW);
            PlanPrice old = plan.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW);
            store(plan);

            Plan loaded = repository.findById(plan.getId()).orElseThrow();
            loaded.replacePrice(old.getId(), new BigDecimal("59.90"), LATER);
            repository.save(loaded);
            entityManager.flush();

            assertThatCode(() -> entityManager.createNativeQuery("SET CONSTRAINTS ALL IMMEDIATE").executeUpdate())
                    .doesNotThrowAnyException();
        }

        @Test
        void anAmountThatIsNotPositiveCannotBeStored() {
            Plan plan = store(Plan.create("Pro", null, NOW));

            assertThatThrownBy(() -> entityManager.createNativeQuery("""
                    INSERT INTO plan_prices (id, plan_id, price, currency, billing_interval, active, created_at)
                    VALUES (gen_random_uuid(), :plan, 0, 'BRL', 'YEARLY', false, now())
                    """).setParameter("plan", plan.getId()).executeUpdate())
                    .isInstanceOf(PersistenceException.class);
        }

        private void insertActivePrice(UUID planId) {
            entityManager.createNativeQuery("""
                    INSERT INTO plan_prices (id, plan_id, price, currency, billing_interval, active, created_at)
                    VALUES (gen_random_uuid(), :plan, 49.90, 'BRL', 'MONTHLY', true, now())
                    """).setParameter("plan", planId).executeUpdate();
        }
    }

    private Plan store(Plan plan) {
        Plan saved = repository.save(plan);
        entityManager.flush();
        entityManager.clear();
        return saved;
    }
}
