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
    @DisplayName("The tax document the gateway requires is a CPF or a CNPJ")
    class Document {

        @Test
        void aCpfIsStoredAsItsDigits() {
            assertThat(SubscriberValidator.document("529.982.247-25")).isEqualTo("52998224725");
            assertThat(SubscriberValidator.document(" 52998224725 ")).isEqualTo("52998224725");
        }

        @Test
        void aCnpjIsStoredAsItsDigits() {
            assertThat(SubscriberValidator.document("11.222.333/0001-81")).isEqualTo("11222333000181");
        }

        @ParameterizedTest
        @ValueSource(strings = { "529.982.247-24", "11.222.333/0001-80", "111.111.111-11", "00000000000000" })
        void aDocumentWhoseCheckDigitsDoNotAddUpIsRefused(String document) {
            assertThatThrownBy(() -> SubscriberValidator.document(document))
                    .isInstanceOf(InvalidSubscriberException.class).hasMessageContaining("document");
        }

        @ParameterizedTest
        @ValueSource(strings = { "5299822472", "529982247250", "abc.def.ghi-jk", "529.982.247-2X" })
        void aDocumentOfAnotherLengthOrWithLettersIsRefused(String document) {
            assertThatThrownBy(() -> SubscriberValidator.document(document))
                    .isInstanceOf(InvalidSubscriberException.class).hasMessageContaining("document");
        }

        @Test
        void aDocumentIsRequired() {
            assertThatThrownBy(() -> SubscriberValidator.document(" "))
                    .isInstanceOf(InvalidSubscriberException.class).hasMessageContaining("document");
            assertThatThrownBy(() -> SubscriberValidator.document(null))
                    .isInstanceOf(InvalidSubscriberException.class).hasMessageContaining("document");
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
