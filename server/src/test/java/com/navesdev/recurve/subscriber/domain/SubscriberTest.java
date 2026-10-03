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
import com.navesdev.recurve.subscriber.domain.exception.InvalidSubscriberException;
import com.navesdev.recurve.subscriber.domain.exception.SubscriberAlreadyCanceledException;
import com.navesdev.recurve.subscriber.domain.exception.SubscriberCanceledException;

/** The rules a subscriber obeys, stated as FR-03 and BR-02/BR-06 state them. */
class SubscriberTest {

    private static final Instant NOW = Instant.parse("2026-01-31T10:00:00Z");
    private static final Instant LATER = Instant.parse("2026-02-10T09:00:00Z");

    @Nested
    @DisplayName("FR-03.1, FR-03.2 registering a subscriber")
    class Registering {

        @Test
        void aSubscriberStartsActiveFromNow() {
            Subscriber subscriber = start(price(BillingInterval.MONTHLY));

            assertThat(subscriber.getId()).isNotNull();
            assertThat(subscriber.getStatus()).isEqualTo(SubscriberStatus.ACTIVE);
            assertThat(subscriber.getStartedAt()).isEqualTo(NOW);
            assertThat(subscriber.getCreatedAt()).isEqualTo(NOW);
            assertThat(subscriber.getCanceledAt()).isNull();
        }

        @Test
        void aSubscriberIsBilledOnTheChosenPrice() {
            PlanPrice price = price(BillingInterval.MONTHLY);

            assertThat(start(price).getPlanPriceId()).isEqualTo(price.getId());
        }

        @Test
        void theFirstChargeIsOneMonthlyCycleAhead() {
            // 31 Jan + one month ends on the last day of February.
            assertThat(start(price(BillingInterval.MONTHLY)).getNextBillingAt())
                    .isEqualTo(Instant.parse("2026-02-28T10:00:00Z"));
        }

        @Test
        void theFirstChargeIsOneYearlyCycleAhead() {
            assertThat(start(price(BillingInterval.YEARLY)).getNextBillingAt())
                    .isEqualTo(Instant.parse("2027-01-31T10:00:00Z"));
        }

        @Test
        void theEmailIsKeptInItsCanonicalForm() {
            Subscriber subscriber = Subscriber.start(" Grace ", " Grace@Navy.Mil ", price(BillingInterval.MONTHLY), NOW);

            assertThat(subscriber.getName()).isEqualTo("Grace");
            assertThat(subscriber.getEmail()).isEqualTo("grace@navy.mil");
        }

        @Test
        void aSubscriberCannotBeBuiltAroundItsRules() {
            // The rules are SubscriberValidator's; this shows the entity goes through them.
            PlanPrice price = price(BillingInterval.MONTHLY);

            assertThatThrownBy(() -> Subscriber.start(" ", "grace@navy.mil", price, NOW))
                    .isInstanceOf(InvalidSubscriberException.class);
            assertThatThrownBy(() -> Subscriber.start("Grace", "not-an-email", price, NOW))
                    .isInstanceOf(InvalidSubscriberException.class);
        }
    }

    @Nested
    @DisplayName("FR-03.7 editing a subscriber")
    class Editing {

        @Test
        void theNameAndEmailChange() {
            Subscriber subscriber = start(price(BillingInterval.MONTHLY));

            subscriber.update("Grace B. Hopper", "GBH@Navy.Mil");

            assertThat(subscriber.getName()).isEqualTo("Grace B. Hopper");
            assertThat(subscriber.getEmail()).isEqualTo("gbh@navy.mil");
        }

        @Test
        void anInvalidEditChangesNothing() {
            Subscriber subscriber = start(price(BillingInterval.MONTHLY));

            assertThatThrownBy(() -> subscriber.update("Grace B. Hopper", "not-an-email"))
                    .isInstanceOf(InvalidSubscriberException.class);
            assertThat(subscriber.getName()).isEqualTo("Grace");
        }

        @Test
        void aCanceledSubscriberCannotBeEdited() {
            Subscriber subscriber = start(price(BillingInterval.MONTHLY));
            subscriber.cancel(LATER);

            assertThatThrownBy(() -> subscriber.update("Grace B. Hopper", "gbh@navy.mil"))
                    .isInstanceOf(SubscriberCanceledException.class);
        }
    }

    @Nested
    @DisplayName("FR-03.3 canceling a subscriber")
    class Canceling {

        @Test
        void theStatusBecomesCanceledAndTheDateIsRecorded() {
            Subscriber subscriber = start(price(BillingInterval.MONTHLY));

            subscriber.cancel(LATER);

            assertThat(subscriber.getStatus()).isEqualTo(SubscriberStatus.CANCELED);
            assertThat(subscriber.getCanceledAt()).isEqualTo(LATER);
        }

        @Test
        void aSubscriberCannotBeCanceledTwice() {
            Subscriber subscriber = start(price(BillingInterval.MONTHLY));
            subscriber.cancel(LATER);

            assertThatThrownBy(() -> subscriber.cancel(LATER.plusSeconds(60)))
                    .isInstanceOf(SubscriberAlreadyCanceledException.class);
            assertThat(subscriber.getCanceledAt()).isEqualTo(LATER);
        }
    }

    private static Subscriber start(PlanPrice price) {
        return Subscriber.start("Grace", "grace@navy.mil", price, NOW);
    }

    private static PlanPrice price(BillingInterval interval) {
        return Plan.create("Pro", null, NOW).addPrice(new BigDecimal("49.90"), "BRL", interval, NOW);
    }
}
