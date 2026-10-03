package com.navesdev.recurve.plan.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.navesdev.recurve.plan.domain.exception.InvalidPlanException;
import com.navesdev.recurve.plan.domain.exception.PlanAlreadyInactiveException;
import com.navesdev.recurve.plan.domain.exception.PlanInactiveException;
import com.navesdev.recurve.plan.domain.exception.PriceAlreadyActiveException;
import com.navesdev.recurve.plan.domain.exception.PriceAlreadyInactiveException;
import com.navesdev.recurve.plan.domain.exception.PriceInactiveException;
import com.navesdev.recurve.plan.domain.exception.PriceNotFoundException;

/** The rules a plan and its prices obey, stated as FR-02 and BR-03/BR-04 state them. */
class PlanTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    private static final Instant LATER = Instant.parse("2026-03-01T10:00:00Z");
    private static final BigDecimal MONTHLY_AMOUNT = new BigDecimal("49.90");

    @Nested
    @DisplayName("FR-02.1 registering a plan")
    class Registering {

        @Test
        void aPlanAcceptsSubscribersAsSoonAsItIsRegistered() {
            Plan plan = Plan.create("Pro", "For teams", NOW);

            assertThat(plan.isActive()).isTrue();
            assertThat(plan.getId()).isNotNull();
            assertThat(plan.getCreatedAt()).isEqualTo(NOW);
        }

        @Test
        void aPlanStartsWithNoPrice() {
            assertThat(Plan.create("Pro", null, NOW).getPrices()).isEmpty();
        }

        @Test
        void aPlanCannotBeBuiltAroundItsRules() {
            // The rules are PlanValidator's; this shows the entity goes through them.
            assertThatThrownBy(() -> Plan.create(" ", null, NOW)).isInstanceOf(InvalidPlanException.class);
        }

        @Test
        void aBlankDescriptionIsNoDescription() {
            assertThat(Plan.create("Pro", "  ", NOW).getDescription()).isNull();
        }
    }

    @Nested
    @DisplayName("FR-02.6 editing a plan")
    class Editing {

        @Test
        void theNameAndDescriptionAreReplaced() {
            Plan plan = Plan.create("Pro", "For teams", NOW);

            plan.update("Pro Plus", null);

            assertThat(plan.getName()).isEqualTo("Pro Plus");
            assertThat(plan.getDescription()).isNull();
        }

        @Test
        void anEditGoesThroughTheSameRules() {
            Plan plan = Plan.create("Pro", null, NOW);

            assertThatThrownBy(() -> plan.update("", null)).isInstanceOf(InvalidPlanException.class);
            assertThat(plan.getName()).isEqualTo("Pro");
        }
    }

    @Nested
    @DisplayName("FR-02.2 pricing a plan")
    class Pricing {

        @Test
        void aPriceIsActiveAndCarriesItsAmountCurrencyAndCycle() {
            Plan plan = Plan.create("Pro", null, NOW);

            PlanPrice price = plan.addPrice(MONTHLY_AMOUNT, "brl", BillingInterval.MONTHLY, NOW);

            assertThat(price.isActive()).isTrue();
            assertThat(price.getPrice()).isEqualByComparingTo("49.90");
            assertThat(price.getCurrency()).isEqualTo("BRL");
            assertThat(price.getInterval()).isEqualTo(BillingInterval.MONTHLY);
            assertThat(plan.getPrices()).containsExactly(price);
        }

        @Test
        void aPlanMayHaveSeveralActivePricesInDifferentCyclesOrCurrencies() {
            Plan plan = Plan.create("Pro", null, NOW);

            plan.addPrice(MONTHLY_AMOUNT, "BRL", BillingInterval.MONTHLY, NOW);
            plan.addPrice(new BigDecimal("499.00"), "BRL", BillingInterval.YEARLY, NOW);
            plan.addPrice(new BigDecimal("9.90"), "USD", BillingInterval.MONTHLY, NOW);

            assertThat(plan.getPrices()).hasSize(3).allMatch(PlanPrice::isActive);
        }

        @Test
        void aSecondActivePriceForTheSameCycleAndCurrencyIsRefused() {
            // BR-03: a new subscriber choosing "monthly in reais" must find one price, not two.
            Plan plan = Plan.create("Pro", null, NOW);
            plan.addPrice(MONTHLY_AMOUNT, "BRL", BillingInterval.MONTHLY, NOW);

            assertThatThrownBy(() -> plan.addPrice(new BigDecimal("59.90"), "brl", BillingInterval.MONTHLY, NOW))
                    .isInstanceOf(PriceAlreadyActiveException.class);
            assertThat(plan.getPrices()).hasSize(1);
        }

        @Test
        void anInactivePriceDoesNotBlockANewOneOnTheSamePair() {
            Plan plan = Plan.create("Pro", null, NOW);
            PlanPrice old = plan.addPrice(MONTHLY_AMOUNT, "BRL", BillingInterval.MONTHLY, NOW);
            plan.deactivatePrice(old.getId());

            plan.addPrice(new BigDecimal("59.90"), "BRL", BillingInterval.MONTHLY, LATER);

            assertThat(plan.getPrices()).hasSize(2);
        }

        @Test
        void anInactivePlanTakesNoNewPrice() {
            Plan plan = Plan.create("Pro", null, NOW);
            plan.deactivate();

            assertThatThrownBy(() -> plan.addPrice(MONTHLY_AMOUNT, "BRL", BillingInterval.MONTHLY, NOW))
                    .isInstanceOf(PlanInactiveException.class);
        }

        @Test
        void aPriceCannotBeBuiltAroundItsRules() {
            Plan plan = Plan.create("Pro", null, NOW);

            assertThatThrownBy(() -> plan.addPrice(BigDecimal.ZERO, "BRL", BillingInterval.MONTHLY, NOW))
                    .isInstanceOf(InvalidPlanException.class);
            assertThatThrownBy(() -> plan.addPrice(MONTHLY_AMOUNT, "XYZ", BillingInterval.MONTHLY, NOW))
                    .isInstanceOf(InvalidPlanException.class);
            assertThat(plan.getPrices()).isEmpty();
        }
    }

    @Nested
    @DisplayName("FR-02.7 / BR-04 replacing a price never edits it")
    class Replacing {

        @Test
        void theSuccessorKeepsTheCycleAndCurrencyAndTakesTheNewAmount() {
            Plan plan = Plan.create("Pro", null, NOW);
            PlanPrice old = plan.addPrice(MONTHLY_AMOUNT, "BRL", BillingInterval.MONTHLY, NOW);

            PlanPrice successor = plan.replacePrice(old.getId(), new BigDecimal("59.90"), LATER);

            assertThat(successor.getId()).isNotEqualTo(old.getId());
            assertThat(successor.getPrice()).isEqualByComparingTo("59.90");
            assertThat(successor.getCurrency()).isEqualTo("BRL");
            assertThat(successor.getInterval()).isEqualTo(BillingInterval.MONTHLY);
            assertThat(successor.isActive()).isTrue();
            assertThat(successor.getCreatedAt()).isEqualTo(LATER);
        }

        @Test
        void theOldPriceStaysOnRecordUnchangedButInactive() {
            // Subscribers on the old price keep paying it (BR-04).
            Plan plan = Plan.create("Pro", null, NOW);
            PlanPrice old = plan.addPrice(MONTHLY_AMOUNT, "BRL", BillingInterval.MONTHLY, NOW);

            plan.replacePrice(old.getId(), new BigDecimal("59.90"), LATER);

            assertThat(old.isActive()).isFalse();
            assertThat(old.getPrice()).isEqualByComparingTo("49.90");
            assertThat(plan.getPrices()).hasSize(2).filteredOn(PlanPrice::isActive).hasSize(1);
        }

        @Test
        void anInactivePriceHasNothingLeftToReplace() {
            Plan plan = Plan.create("Pro", null, NOW);
            PlanPrice old = plan.addPrice(MONTHLY_AMOUNT, "BRL", BillingInterval.MONTHLY, NOW);
            plan.deactivatePrice(old.getId());

            assertThatThrownBy(() -> plan.replacePrice(old.getId(), new BigDecimal("59.90"), LATER))
                    .isInstanceOf(PriceInactiveException.class);
        }

        @Test
        void anInactivePlanTakesNoReplacement() {
            Plan plan = Plan.create("Pro", null, NOW);
            PlanPrice old = plan.addPrice(MONTHLY_AMOUNT, "BRL", BillingInterval.MONTHLY, NOW);
            plan.deactivate();

            assertThatThrownBy(() -> plan.replacePrice(old.getId(), new BigDecimal("59.90"), LATER))
                    .isInstanceOf(PlanInactiveException.class);
            assertThat(old.isActive()).isTrue();
        }

        @Test
        void anInvalidAmountLeavesTheOldPriceActive() {
            Plan plan = Plan.create("Pro", null, NOW);
            PlanPrice old = plan.addPrice(MONTHLY_AMOUNT, "BRL", BillingInterval.MONTHLY, NOW);

            assertThatThrownBy(() -> plan.replacePrice(old.getId(), new BigDecimal("-1"), LATER))
                    .isInstanceOf(InvalidPlanException.class);
            assertThat(old.isActive()).isTrue();
            assertThat(plan.getPrices()).hasSize(1);
        }

        @Test
        void aPriceThePlanDoesNotHaveIsNotFound() {
            Plan plan = Plan.create("Pro", null, NOW);

            assertThatThrownBy(() -> plan.replacePrice(UUID.randomUUID(), MONTHLY_AMOUNT, LATER))
                    .isInstanceOf(PriceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("FR-02.3 / FR-02.4 deactivating, never deleting")
    class Deactivating {

        @Test
        void aDeactivatedPriceStaysOnRecord() {
            Plan plan = Plan.create("Pro", null, NOW);
            PlanPrice price = plan.addPrice(MONTHLY_AMOUNT, "BRL", BillingInterval.MONTHLY, NOW);

            plan.deactivatePrice(price.getId());

            assertThat(plan.getPrices()).containsExactly(price);
            assertThat(price.isActive()).isFalse();
        }

        @Test
        void aPriceIsDeactivatedOnlyOnce() {
            Plan plan = Plan.create("Pro", null, NOW);
            PlanPrice price = plan.addPrice(MONTHLY_AMOUNT, "BRL", BillingInterval.MONTHLY, NOW);
            plan.deactivatePrice(price.getId());

            assertThatThrownBy(() -> plan.deactivatePrice(price.getId()))
                    .isInstanceOf(PriceAlreadyInactiveException.class);
        }

        @Test
        void deactivatingAPlanLeavesItsPricesAsTheyWere() {
            // An inactive plan refuses subscribers by itself; its prices
            // keep saying which ones were in force.
            Plan plan = Plan.create("Pro", null, NOW);
            PlanPrice price = plan.addPrice(MONTHLY_AMOUNT, "BRL", BillingInterval.MONTHLY, NOW);

            plan.deactivate();

            assertThat(plan.isActive()).isFalse();
            assertThat(price.isActive()).isTrue();
        }

        @Test
        void aPlanIsDeactivatedOnlyOnce() {
            Plan plan = Plan.create("Pro", null, NOW);
            plan.deactivate();

            assertThatThrownBy(plan::deactivate).isInstanceOf(PlanAlreadyInactiveException.class);
        }

        @Test
        void aPriceThePlanDoesNotHaveIsNotFound() {
            Plan plan = Plan.create("Pro", null, NOW);

            assertThatThrownBy(() -> plan.deactivatePrice(UUID.randomUUID()))
                    .isInstanceOf(PriceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("FR-02.3, FR-02.4 which price takes a new subscriber")
    class Subscribing {

        @Test
        void anActivePriceOfAnActivePlanTakesANewSubscriber() {
            Plan plan = Plan.create("Pro", null, NOW);
            PlanPrice price = plan.addPrice(MONTHLY_AMOUNT, "BRL", BillingInterval.MONTHLY, NOW);

            assertThat(plan.subscribablePrice(price.getId())).isSameAs(price);
        }

        @Test
        void anInactivePlanTakesNoNewSubscriber() {
            Plan plan = Plan.create("Pro", null, NOW);
            PlanPrice price = plan.addPrice(MONTHLY_AMOUNT, "BRL", BillingInterval.MONTHLY, NOW);
            plan.deactivate();

            assertThatThrownBy(() -> plan.subscribablePrice(price.getId()))
                    .isInstanceOf(PlanInactiveException.class);
        }

        @Test
        void anInactivePriceTakesNoNewSubscriber() {
            Plan plan = Plan.create("Pro", null, NOW);
            PlanPrice price = plan.addPrice(MONTHLY_AMOUNT, "BRL", BillingInterval.MONTHLY, NOW);
            plan.deactivatePrice(price.getId());

            assertThatThrownBy(() -> plan.subscribablePrice(price.getId()))
                    .isInstanceOf(PriceInactiveException.class);
        }

        @Test
        void aPriceThePlanDoesNotHaveIsNotFound() {
            Plan plan = Plan.create("Pro", null, NOW);

            assertThatThrownBy(() -> plan.subscribablePrice(UUID.randomUUID()))
                    .isInstanceOf(PriceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("The prices change only through the plan")
    class Encapsulation {

        @Test
        void theListOfPricesCannotBeChangedFromOutside() {
            Plan plan = Plan.create("Pro", null, NOW);

            assertThatThrownBy(() -> plan.getPrices().add(null)).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void pricesAreListedInTheOrderTheyWereCreated() {
            Plan plan = Plan.create("Pro", null, NOW);
            PlanPrice first = plan.addPrice(MONTHLY_AMOUNT, "BRL", BillingInterval.MONTHLY, NOW);
            PlanPrice second = plan.replacePrice(first.getId(), new BigDecimal("59.90"), LATER);

            assertThat(plan.getPrices()).containsExactly(first, second);
        }
    }
}
