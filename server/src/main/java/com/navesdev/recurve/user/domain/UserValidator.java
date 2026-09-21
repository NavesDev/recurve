package com.navesdev.recurve.user.domain;

import java.util.Locale;
import java.util.regex.Pattern;

import com.navesdev.recurve.user.domain.exception.InvalidUserException;

/**
 * The invariants of an operator's attributes, kept apart from
 * {@link User} so the entity reads as behaviour and this as rules. Every
 * attribute enters the entity through here — on creation and on every
 * change — so a {@code User} that exists is a valid one, whether it came
 * through the API, the startup bootstrap or a caller not written yet.
 *
 * <p>Bean Validation on the request records repeats the same limits. That
 * is not this rule twice: the edge answers "what did the caller get
 * wrong", field by field, in one 400; this answers "can this exist", and
 * holds for callers that never pass the edge.
 *
 * <p>Each limit is the column's: the database would refuse the value
 * anyway, but as a constraint violation deep in a flush, naming nothing.
 */
public final class UserValidator {

    public static final int NAME_MAX_LENGTH = 120;
    public static final int EMAIL_MAX_LENGTH = 255;
    public static final int PASSWORD_HASH_MAX_LENGTH = 100;

    /**
     * One {@code @}, something on each side, no whitespace. Deliberately no
     * stricter than the edge's {@code @Email}: a value the edge accepts and
     * this refuses would turn a field error into a rule violation.
     */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+$");

    private UserValidator() {
    }

    public static String name(String name) {
        return text(name, "name", NAME_MAX_LENGTH);
    }

    /**
     * BR-02: the operator email is compared and stored in its canonical
     * form — see {@link #normalizeEmail}. Validated after normalizing, so
     * the length that counts is the one that gets stored.
     */
    public static String email(String email) {
        String normalized = text(email, "email", EMAIL_MAX_LENGTH);
        normalized = normalizeEmail(normalized);

        if (!EMAIL.matcher(normalized).matches()) {
            throw new InvalidUserException("email is not a valid address: '%s'".formatted(normalized));
        }
        return normalized;
    }

    /**
     * The canonical form of an email: trimmed, lower-cased. The one rule
     * for every comparison — uniqueness, login lookup, the entity itself —
     * so no two places can disagree on whether two spellings are the same
     * operator. Normalizes only; a lookup by a malformed address simply
     * finds nobody.
     */
    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    public static String passwordHash(String passwordHash) {
        return text(passwordHash, "passwordHash", PASSWORD_HASH_MAX_LENGTH);
    }

    private static String text(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new InvalidUserException("%s is required".formatted(field));
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new InvalidUserException(
                    "%s must be at most %d characters, got %d".formatted(field, maxLength, trimmed.length()));
        }
        return trimmed;
    }
}
