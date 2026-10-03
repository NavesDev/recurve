package com.navesdev.recurve.subscriber.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.navesdev.recurve.subscriber.domain.exception.InvalidSubscriberException;

/** What may enter a {@link Subscriber}, stated once, for every caller. */
class SubscriberValidatorTest {

    @Nested
    @DisplayName("The name is required and bounded by its column")
    class Name {

        @Test
        void aBlankNameIsRefusedAndNamesTheField() {
            assertThatThrownBy(() -> SubscriberValidator.name("  "))
                    .isInstanceOf(InvalidSubscriberException.class).hasMessageContaining("name");
            assertThatThrownBy(() -> SubscriberValidator.name(null))
                    .isInstanceOf(InvalidSubscriberException.class).hasMessageContaining("name");
        }

        @Test
        void surroundingWhitespaceIsDropped() {
            assertThat(SubscriberValidator.name("  Grace Hopper ")).isEqualTo("Grace Hopper");
        }

        @Test
        void aNameLongerThanItsColumnIsRefused() {
            String tooLong = "a".repeat(SubscriberValidator.NAME_MAX_LENGTH + 1);

            assertThatThrownBy(() -> SubscriberValidator.name(tooLong))
                    .isInstanceOf(InvalidSubscriberException.class)
                    .hasMessageContaining(String.valueOf(SubscriberValidator.NAME_MAX_LENGTH));
            assertThat(SubscriberValidator.name("a".repeat(SubscriberValidator.NAME_MAX_LENGTH)))
                    .hasSize(SubscriberValidator.NAME_MAX_LENGTH);
        }
    }

    @Nested
    @DisplayName("BR-02 an email has one canonical form")
    class Email {

        @Test
        void isStoredTrimmedAndLowerCased() {
            assertThat(SubscriberValidator.email("  Grace@Navy.Mil ")).isEqualTo("grace@navy.mil");
        }

        @Test
        void theSameFormIsUsedForLookupsWithoutJudgingThem() {
            assertThat(SubscriberValidator.normalizeEmail(" Not-An-Email ")).isEqualTo("not-an-email");
            assertThat(SubscriberValidator.normalizeEmail(null)).isNull();
        }

        @Test
        void aBlankEmailIsRefused() {
            assertThatThrownBy(() -> SubscriberValidator.email(" "))
                    .isInstanceOf(InvalidSubscriberException.class).hasMessageContaining("email");
        }

        @ParameterizedTest
        @ValueSource(strings = { "grace", "@navy.mil", "grace@", "grace @navy.mil", "grace@@navy.mil" })
        void anAddressWithoutOneAtAndSomethingOnEachSideIsRefused(String malformed) {
            assertThatThrownBy(() -> SubscriberValidator.email(malformed))
                    .isInstanceOf(InvalidSubscriberException.class)
                    .hasMessageContaining("email");
        }

        @Test
        void anEmailLongerThanItsColumnIsRefused() {
            String tooLong = "a".repeat(SubscriberValidator.EMAIL_MAX_LENGTH - "@navy.mil".length() + 1) + "@navy.mil";

            assertThatThrownBy(() -> SubscriberValidator.email(tooLong))
                    .isInstanceOf(InvalidSubscriberException.class)
                    .hasMessageContaining(String.valueOf(SubscriberValidator.EMAIL_MAX_LENGTH));
        }
    }
}
