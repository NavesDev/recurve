package com.navesdev.recurve.user.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.navesdev.recurve.user.domain.exception.InvalidUserException;

/** What may enter a {@link User}, stated once, for every caller. */
class UserValidatorTest {

    @Nested
    @DisplayName("Text attributes are required and bounded by their column")
    class Text {

        @Test
        void aBlankValueIsRefusedAndNamesTheField() {
            assertThatThrownBy(() -> UserValidator.name("  "))
                    .isInstanceOf(InvalidUserException.class).hasMessageContaining("name");
            assertThatThrownBy(() -> UserValidator.passwordHash(null))
                    .isInstanceOf(InvalidUserException.class).hasMessageContaining("passwordHash");
        }

        @Test
        void surroundingWhitespaceIsDropped() {
            assertThat(UserValidator.name("  Ada Lovelace ")).isEqualTo("Ada Lovelace");
        }

        @Test
        void aValueLongerThanItsColumnIsRefused() {
            String tooLong = "a".repeat(UserValidator.NAME_MAX_LENGTH + 1);

            assertThatThrownBy(() -> UserValidator.name(tooLong))
                    .isInstanceOf(InvalidUserException.class)
                    .hasMessageContaining(String.valueOf(UserValidator.NAME_MAX_LENGTH));
            assertThat(UserValidator.name("a".repeat(UserValidator.NAME_MAX_LENGTH))).hasSize(UserValidator.NAME_MAX_LENGTH);
        }
    }

    @Nested
    @DisplayName("BR-02 an email has one canonical form")
    class Email {

        @Test
        void isStoredTrimmedAndLowerCased() {
            assertThat(UserValidator.email("  Ada@Recurve.Local ")).isEqualTo("ada@recurve.local");
        }

        @Test
        void theSameFormIsUsedForLookupsWithoutJudgingThem() {
            // A login attempt with a malformed address must find nobody,
            // not fail: normalizing is not validating.
            assertThat(UserValidator.normalizeEmail(" Not-An-Email ")).isEqualTo("not-an-email");
            assertThat(UserValidator.normalizeEmail(null)).isNull();
        }

        @ParameterizedTest
        @ValueSource(strings = { "ada", "@recurve.local", "ada@", "ada @recurve.local", "ada@@recurve.local" })
        void anAddressWithoutOneAtAndSomethingOnEachSideIsRefused(String malformed) {
            assertThatThrownBy(() -> UserValidator.email(malformed))
                    .isInstanceOf(InvalidUserException.class)
                    .hasMessageContaining("email");
        }

        @Test
        void isBoundedByItsColumnAfterNormalizing() {
            String local = "a".repeat(UserValidator.EMAIL_MAX_LENGTH - "@x.io".length());

            assertThat(UserValidator.email(local + "@x.io")).hasSize(UserValidator.EMAIL_MAX_LENGTH);
            assertThatThrownBy(() -> UserValidator.email(local + "a@x.io"))
                    .isInstanceOf(InvalidUserException.class);
        }
    }
}
