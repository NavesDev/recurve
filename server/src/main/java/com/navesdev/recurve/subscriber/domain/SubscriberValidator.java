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
     * The canonical form of an email: trimmed, lower-cased. The one rule
     * for the entity and the uniqueness check alike. Normalizes only.
     */
    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
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
