package com.navesdev.recurve.subscriber.domain;

import java.util.Locale;
import java.util.regex.Pattern;

import com.navesdev.recurve.subscriber.domain.exception.InvalidSubscriberException;

/**
 * The invariants of a subscriber's attributes, kept apart from
 * {@link Subscriber} so the entity reads as behaviour and this as rules.
 * Every attribute enters the entity through here — on creation and on
 * every change — so a {@code Subscriber} that exists is a valid one.
 *
 * <p>Bean Validation on the request records repeats these limits, taking
 * them from the constants below: the edge answers "what did the caller
 * get wrong", field by field; this answers "can this exist". Each limit is
 * the column's.
 *
 * <p>The email rules are {@code UserValidator}'s. Two features, two
 * uniqueness rules (BR-02), one notion of what an address is; giving that
 * notion one home is a refactor of its own.
 */
public final class SubscriberValidator {

    public static final int NAME_MAX_LENGTH = 120;
    public static final int EMAIL_MAX_LENGTH = 255;
    public static final int CPF_LENGTH = 11;
    public static final int CNPJ_LENGTH = 14;
    /** A CNPJ written with its punctuation, {@code 11.222.333/0001-81}: the longest a document arrives. */
    public static final int DOCUMENT_MAX_LENGTH = 18;

    /** The punctuation a CPF or CNPJ is usually written with; nothing else is dropped. */
    private static final Pattern DOCUMENT_PUNCTUATION = Pattern.compile("[.\\-/\\s]");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final int[] CNPJ_WEIGHTS = { 6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2 };

    /** One {@code @}, something on each side, no whitespace — no stricter than the edge's {@code @Email}. */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+$");

    private SubscriberValidator() {
    }

    public static String name(String name) {
        return text(name, "name", NAME_MAX_LENGTH);
    }

    /** BR-02: stored and compared in its canonical form — see {@link #normalizeEmail}. */
    public static String email(String email) {
        String normalized = normalizeEmail(text(email, "email", EMAIL_MAX_LENGTH));
        if (!EMAIL.matcher(normalized).matches()) {
            throw new InvalidSubscriberException("email is not a valid address: '%s'".formatted(normalized));
        }
        return normalized;
    }

    /**
     * The tax document the payment gateway requires of a customer: a CPF
     * (11 digits) or a CNPJ (14), stored as digits only. Written with or
     * without its usual punctuation; refused when its check digits do not
     * add up, or when every digit is the same — those pass the arithmetic
     * and are no one's.
     */
    public static String document(String document) {
        if (document == null || document.isBlank()) {
            throw new InvalidSubscriberException("document is required");
        }
        String digits = DOCUMENT_PUNCTUATION.matcher(document).replaceAll("");
        if (!hasDocumentShape(digits)) {
            throw new InvalidSubscriberException(
                    "document must be a CPF of %d digits or a CNPJ of %d".formatted(CPF_LENGTH, CNPJ_LENGTH));
        }
        if (!hasValidCheckDigits(digits)) {
            throw new InvalidSubscriberException("document is not a valid CPF or CNPJ");
        }
        return digits;
    }

    private static boolean hasDocumentShape(String digits) {
        boolean length = digits.length() == CPF_LENGTH || digits.length() == CNPJ_LENGTH;
        return length && DIGITS.matcher(digits).matches();
    }

    private static boolean hasValidCheckDigits(String digits) {
        if (digits.chars().distinct().count() == 1) {
            return false;
        }
        return digits.length() == CPF_LENGTH ? isCpf(digits) : isCnpj(digits);
    }

    /**
     * The canonical form of an email: trimmed, lower-cased. The one rule
     * for the entity and the uniqueness check alike. Normalizes only.
     */
    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isCpf(String digits) {
        return cpfCheckDigit(digits, 9) == digit(digits, 9) && cpfCheckDigit(digits, 10) == digit(digits, 10);
    }

    private static boolean isCnpj(String digits) {
        return cnpjCheckDigit(digits, 12) == digit(digits, 12) && cnpjCheckDigit(digits, 13) == digit(digits, 13);
    }

    /** CPF: weights count down from {@code length + 1} to 2 over the first {@code length} digits. */
    private static int cpfCheckDigit(String digits, int length) {
        int sum = 0;
        for (int i = 0; i < length; i++) {
            sum += digit(digits, i) * (length + 1 - i);
        }
        int rest = sum * 10 % 11;
        return rest == 10 ? 0 : rest;
    }

    /** CNPJ: the fixed weights, shifted by one for the second digit. */
    private static int cnpjCheckDigit(String digits, int length) {
        int offset = CNPJ_WEIGHTS.length - length;
        int sum = 0;
        for (int i = 0; i < length; i++) {
            sum += digit(digits, i) * CNPJ_WEIGHTS[offset + i];
        }
        int rest = sum % 11;
        return rest < 2 ? 0 : 11 - rest;
    }

    private static int digit(String digits, int index) {
        return digits.charAt(index) - '0';
    }

    private static String text(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new InvalidSubscriberException("%s is required".formatted(field));
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new InvalidSubscriberException(
                    "%s must be at most %d characters, got %d".formatted(field, maxLength, trimmed.length()));
        }
        return trimmed;
    }
}
