package com.navesdev.recurve.subscriber.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.navesdev.recurve.plan.domain.BillingInterval;
import com.navesdev.recurve.plan.domain.Plan;
import com.navesdev.recurve.plan.domain.PlanPrice;
import com.navesdev.recurve.plan.domain.exception.PriceNotFoundException;

/** What the listing shows of a subscriber, and what it filters and sorts on (FR-06.3). */
class SubscriberSummaryTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    private static final Instant LATER = Instant.parse("2026-03-01T10:00:00Z");

    @Nested
    @DisplayName("FR-03.4 the listing shows a subscriber with what it pays")
    class Projection {

        @Test
        void everyFieldOfTheSubscriberIsShown() {
            Plan plan = Plan.create("Pro", null, NOW);
            PlanPrice price = plan.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW);
            Subscriber subscriber = Subscriber.start("Grace", "grace@navy.mil", price, NOW);
            subscriber.cancel(LATER);

            SubscriberSummary summary = SubscriberSummary.of(subscriber, plan);

            assertThat(summary.id()).isEqualTo(subscriber.getId());
            assertThat(summary.name()).isEqualTo("Grace");
            assertThat(summary.email()).isEqualTo("grace@navy.mil");
            assertThat(summary.status()).isEqualTo(SubscriberStatus.CANCELED);
            assertThat(summary.startedAt()).isEqualTo(NOW);
            assertThat(summary.nextBillingAt()).isEqualTo(subscriber.getNextBillingAt());
            assertThat(summary.canceledAt()).isEqualTo(LATER);
            assertThat(summary.createdAt()).isEqualTo(NOW);
        }

        @Test
        void thePriceAndItsPlanAreCopiedSoTheListingCanFilterAndSortOnThem() {
            Plan plan = Plan.create("Pro", null, NOW);
            PlanPrice price = plan.addPrice(new BigDecimal("499.00"), "USD", BillingInterval.YEARLY, NOW);

            SubscriberSummary summary = SubscriberSummary.of(Subscriber.start("Grace", "grace@navy.mil", price, NOW), plan);

            assertThat(summary.planId()).isEqualTo(plan.getId());
            assertThat(summary.planPriceId()).isEqualTo(price.getId());
            assertThat(summary.currency()).isEqualTo("USD");
            assertThat(summary.interval()).isEqualTo(BillingInterval.YEARLY);
        }

        @Test
        void anAmountTravelsAsTextSoItNeverPassesThroughADouble() {
            Plan plan = Plan.create("Pro", null, NOW);
            PlanPrice price = plan.addPrice(new BigDecimal("49.9"), "BRL", BillingInterval.MONTHLY, NOW);

            assertThat(SubscriberSummary.of(Subscriber.start("Grace", "grace@navy.mil", price, NOW), plan).price())
                    .isEqualTo("49.90");
        }

        @Test
        void aReplacedPriceIsStillTheOneShown() {
            // BR-04: the subscriber stays on the price it chose.
            Plan plan = Plan.create("Pro", null, NOW);
            PlanPrice old = plan.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW);
            Subscriber subscriber = Subscriber.start("Grace", "grace@navy.mil", old, NOW);
            plan.replacePrice(old.getId(), new BigDecimal("59.90"), LATER);

            assertThat(SubscriberSummary.of(subscriber, plan).price()).isEqualTo("49.90");
        }

        @Test
        void aPlanThatDoesNotHoldThePriceIsAMistake() {
            Plan plan = Plan.create("Pro", null, NOW);
            PlanPrice price = plan.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW);
            Subscriber subscriber = Subscriber.start("Grace", "grace@navy.mil", price, NOW);

            assertThatThrownBy(() -> SubscriberSummary.of(subscriber, Plan.create("Basic", null, NOW)))
                    .isInstanceOf(PriceNotFoundException.class);
        }
    }
}
