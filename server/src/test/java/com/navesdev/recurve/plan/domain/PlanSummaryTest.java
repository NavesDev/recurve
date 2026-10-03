package com.navesdev.recurve.plan.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** What the listing shows of a plan (FR-02.5), and what it filters on (FR-06.2). */
class PlanSummaryTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    @Nested
    @DisplayName("FR-02.5 the listing shows a plan with its prices")
    class Projection {

        @Test
        void everyFieldTheListingShowsComesFromThePlan() {
            Plan plan = Plan.create("Pro", "For teams", NOW);
            PlanPrice price = plan.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW);

            PlanSummary summary = PlanSummary.of(plan);

            assertThat(summary.id()).isEqualTo(plan.getId());
            assertThat(summary.name()).isEqualTo("Pro");
            assertThat(summary.description()).isEqualTo("For teams");
            assertThat(summary.active()).isTrue();
            assertThat(summary.createdAt()).isEqualTo(NOW);
            assertThat(summary.prices()).singleElement().satisfies(shown -> {
                assertThat(shown.id()).isEqualTo(price.getId());
                assertThat(shown.currency()).isEqualTo("BRL");
                assertThat(shown.interval()).isEqualTo(BillingInterval.MONTHLY);
                assertThat(shown.active()).isTrue();
                assertThat(shown.createdAt()).isEqualTo(NOW);
            });
        }

        @Test
        void anAmountTravelsAsTextSoItNeverPassesThroughADouble() {
            Plan plan = Plan.create("Pro", null, NOW);
            plan.addPrice(new BigDecimal("49.9"), "BRL", BillingInterval.MONTHLY, NOW);

            assertThat(PlanSummary.of(plan).prices().getFirst().price()).isEqualTo("49.90");
        }

        @Test
        void inactivePricesAreShownToo() {
            // BR-04: the history of a plan's prices is the point of never editing one.
            Plan plan = Plan.create("Pro", null, NOW);
            PlanPrice old = plan.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW);
            plan.replacePrice(old.getId(), new BigDecimal("59.90"), NOW);

            assertThat(PlanSummary.of(plan).prices()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("FR-06.2 the cycles a plan is on sale in")
    class ActiveIntervals {

        @Test
        void onlyActivePricesCount() {
            Plan plan = Plan.create("Pro", null, NOW);
            PlanPrice monthly = plan.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW);
            plan.addPrice(new BigDecimal("499.00"), "BRL", BillingInterval.YEARLY, NOW);
            plan.deactivatePrice(monthly.getId());

            assertThat(PlanSummary.of(plan).activeIntervals()).containsExactly(BillingInterval.YEARLY);
        }

        @Test
        void aPlanWithNoActivePriceIsOnSaleInNoCycle() {
            assertThat(PlanSummary.of(Plan.create("Pro", null, NOW)).activeIntervals()).isEmpty();
        }

        @Test
        void thePlansOwnStateIsAFilterOfItsOwn() {
            // activeIntervals answers "which cycles have a price in force";
            // whether the plan itself is active is the active filter's job.
            Plan plan = Plan.create("Pro", null, NOW);
            plan.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW);
            plan.deactivate();

            PlanSummary summary = PlanSummary.of(plan);

            assertThat(summary.active()).isFalse();
            assertThat(summary.activeIntervals()).containsExactly(BillingInterval.MONTHLY);
        }
    }
}
