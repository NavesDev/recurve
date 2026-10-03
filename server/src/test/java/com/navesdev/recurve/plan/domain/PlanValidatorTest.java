package com.navesdev.recurve.plan.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.navesdev.recurve.plan.domain.exception.InvalidPlanException;

/** What may enter a {@link Plan} or a {@link PlanPrice}, stated once, for every caller. */
class PlanValidatorTest {

    @Nested
    @DisplayName("FR-02.1 a plan has a name and may have a description")
    class Text {

        @Test
        void aBlankNameIsRefusedAndNamesTheField() {
            assertThatThrownBy(() -> PlanValidator.name("  "))
                    .isInstanceOf(InvalidPlanException.class).hasMessageContaining("name");
            assertThatThrownBy(() -> PlanValidator.name(null))
                    .isInstanceOf(InvalidPlanException.class).hasMessageContaining("name");
        }

        @Test
        void aNameIsStoredTrimmed() {
            assertThat(PlanValidator.name("  Pro ")).isEqualTo("Pro");
        }

        @Test
        void aNameLongerThanItsColumnIsRefused() {
            String tooLong = "a".repeat(PlanValidator.NAME_MAX_LENGTH + 1);

            assertThatThrownBy(() -> PlanValidator.name(tooLong))
                    .isInstanceOf(InvalidPlanException.class)
                    .hasMessageContaining(String.valueOf(PlanValidator.NAME_MAX_LENGTH));
        }

        @Test
        void anAbsentOrBlankDescriptionIsNoDescription() {
            assertThat(PlanValidator.description(null)).isNull();
            assertThat(PlanValidator.description("   ")).isNull();
        }

        @Test
        void aDescriptionIsStoredTrimmed() {
            assertThat(PlanValidator.description("  For teams ")).isEqualTo("For teams");
        }

        @Test
        void aDescriptionLongerThanItsColumnIsRefused() {
            String tooLong = "a".repeat(PlanValidator.DESCRIPTION_MAX_LENGTH + 1);

            assertThatThrownBy(() -> PlanValidator.description(tooLong))
                    .isInstanceOf(InvalidPlanException.class).hasMessageContaining("description");
        }
    }

    @Nested
    @DisplayName("NFR-06 an amount is positive, with two decimal places")
    class Amount {

        @Test
        void anAbsentAmountIsRefused() {
            assertThatThrownBy(() -> PlanValidator.price(null))
                    .isInstanceOf(InvalidPlanException.class).hasMessageContaining("price");
        }

        @ParameterizedTest
        @ValueSource(strings = { "0", "0.00", "-1.00" })
        void anAmountThatIsNotPositiveIsRefused(String amount) {
            assertThatThrownBy(() -> PlanValidator.price(new BigDecimal(amount)))
                    .isInstanceOf(InvalidPlanException.class).hasMessageContaining("greater than zero");
        }

        @Test
        void anAmountWithMoreThanTwoDecimalPlacesIsRefused() {
            assertThatThrownBy(() -> PlanValidator.price(new BigDecimal("12.345")))
                    .isInstanceOf(InvalidPlanException.class).hasMessageContaining("decimal");
        }

        @Test
        void trailingZerosBeyondTheSecondPlaceAreNotPrecision() {
            assertThat(PlanValidator.price(new BigDecimal("12.300"))).isEqualByComparingTo("12.30");
        }

        @Test
        void anAmountTooLargeForItsColumnIsRefused() {
            assertThatThrownBy(() -> PlanValidator.price(new BigDecimal("12345678901.00")))
                    .isInstanceOf(InvalidPlanException.class).hasMessageContaining("digits");
            assertThat(PlanValidator.price(new BigDecimal("9999999999.99"))).isEqualByComparingTo("9999999999.99");
        }

        @Test
        void everyAmountIsStoredWithExactlyTwoDecimalPlaces() {
            assertThat(PlanValidator.price(new BigDecimal("49.9")).toPlainString()).isEqualTo("49.90");
            assertThat(PlanValidator.price(new BigDecimal("1E+2")).toPlainString()).isEqualTo("100.00");
        }
    }

    @Nested
    @DisplayName("NFR-06 a currency is BRL, the one the gateway charges in")
    class CurrencyCode {

        @ParameterizedTest
        @ValueSource(strings = { "USD", "EUR", "usd" })
        void aRealCurrencyOtherThanBrlIsRefused(String code) {
            assertThatThrownBy(() -> PlanValidator.currency(code))
                    .isInstanceOf(InvalidPlanException.class).hasMessageContaining("BRL");
        }

        @Test
        void aCodeIsStoredUpperCased() {
            assertThat(PlanValidator.currency(" brl ")).isEqualTo("BRL");
        }

        @ParameterizedTest
        @ValueSource(strings = { "XYZ", "BR", "REAL", "" })
        void aCodeThatIsNotACurrencyIsRefused(String code) {
            assertThatThrownBy(() -> PlanValidator.currency(code))
                    .isInstanceOf(InvalidPlanException.class).hasMessageContaining("currency");
        }

        @Test
        void anAbsentCodeIsRefused() {
            assertThatThrownBy(() -> PlanValidator.currency(null))
                    .isInstanceOf(InvalidPlanException.class).hasMessageContaining("currency");
        }
    }

    @Nested
    @DisplayName("FR-02.2 a price has a billing cycle")
    class Cycle {

        @Test
        void anAbsentCycleIsRefused() {
            assertThatThrownBy(() -> PlanValidator.interval(null))
                    .isInstanceOf(InvalidPlanException.class).hasMessageContaining("interval");
        }

        @Test
        void aCycleIsTakenAsGiven() {
            assertThat(PlanValidator.interval(BillingInterval.YEARLY)).isEqualTo(BillingInterval.YEARLY);
        }
    }
}
