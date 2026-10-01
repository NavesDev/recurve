package com.navesdev.recurve.plan.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** How far one billing cycle reaches (FR-03.2, FR-04.2). */
class BillingIntervalTest {

    @Nested
    @DisplayName("One cycle ahead, on the UTC calendar")
    class Advancing {

        @Test
        void aMonthlyCycleEndsOnTheSameDayOfTheNextMonth() {
            assertThat(BillingInterval.MONTHLY.advance(Instant.parse("2026-01-15T10:00:00Z")))
                    .isEqualTo(Instant.parse("2026-02-15T10:00:00Z"));
        }

        @Test
        void aYearlyCycleEndsOnTheSameDayOfTheNextYear() {
            assertThat(BillingInterval.YEARLY.advance(Instant.parse("2026-01-15T10:00:00Z")))
                    .isEqualTo(Instant.parse("2027-01-15T10:00:00Z"));
        }

        @Test
        void aMonthThatIsTooShortEndsTheCycleOnItsLastDay() {
            assertThat(BillingInterval.MONTHLY.advance(Instant.parse("2026-01-31T10:00:00Z")))
                    .isEqualTo(Instant.parse("2026-02-28T10:00:00Z"));
            assertThat(BillingInterval.YEARLY.advance(Instant.parse("2028-02-29T10:00:00Z")))
                    .isEqualTo(Instant.parse("2029-02-28T10:00:00Z"));
        }
    }
}
