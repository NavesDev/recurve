package com.navesdev.recurve.plan.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Locale;

import com.navesdev.recurve.plan.domain.exception.InvalidPlanException;

/**
 * The invariants of a plan's and a price's attributes, kept apart from
 * the entities so they read as behaviour and this as rules. Every
 * attribute enters through here, on creation and on every change, so a
 * {@code Plan} that exists is a valid one whoever built it.
 *
 * <p>Bean Validation on the request records repeats these limits, taking
 * them from the constants below: the edge answers "what did the caller
 * get wrong", field by field; this answers "can this exist".
 */
public final class PlanValidator {

    public static final int NAME_MAX_LENGTH = 120;
    public static final int DESCRIPTION_MAX_LENGTH = 500;

    /** NFR-06 and {@code numeric(12,2)}: ten digits before the point, two after. */
    public static final String PRICE_MIN = "0.01";
    public static final int PRICE_INTEGER_DIGITS = 10;
    public static final int PRICE_FRACTION_DIGITS = 2;

    /** NFR-06: the only currency a price may be in. */
    public static final String SUPPORTED_CURRENCY = "BRL";

    /** The shape the edge checks; whether the code is a currency is decided here. */
    public static final String CURRENCY_PATTERN = "^[A-Za-z]{3}$";

    private PlanValidator() {
    }

    public static String name(String name) {
        if (name == null || name.isBlank()) {
            throw new InvalidPlanException("name is required");
        }
        return bounded(name.trim(), "name", NAME_MAX_LENGTH);
    }

    /** Optional: absent and blank both mean the plan has none. */
    public static String description(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        return bounded(description.trim(), "description", DESCRIPTION_MAX_LENGTH);
    }

    /**
     * BR-05 copies this amount into every charge, so it is stored in one
     * form: exactly two decimal places. Trailing zeros are not precision —
     * {@code 12.300} is {@code 12.30} — but a third significant place is
     * refused rather than rounded away.
     */
    public static BigDecimal price(BigDecimal price) {
        if (price == null) {
            throw new InvalidPlanException("price is required");
        }
        if (price.signum() <= 0) {
            throw new InvalidPlanException("price must be greater than zero, got %s".formatted(price.toPlainString()));
        }
        BigDecimal stripped = price.stripTrailingZeros();
        if (stripped.scale() > PRICE_FRACTION_DIGITS) {
            throw new InvalidPlanException("price must have at most %d decimal places, got %s"
                    .formatted(PRICE_FRACTION_DIGITS, price.toPlainString()));
        }
        BigDecimal normalized = stripped.setScale(PRICE_FRACTION_DIGITS, RoundingMode.UNNECESSARY);
        if (normalized.precision() - normalized.scale() > PRICE_INTEGER_DIGITS) {
            throw new InvalidPlanException("price must have at most %d integer digits, got %s"
                    .formatted(PRICE_INTEGER_DIGITS, price.toPlainString()));
        }
        return normalized;
    }

    /**
     * NFR-06: an ISO 4217 code the JDK knows, upper-cased ({@code brl} is
     * {@code BRL}) — and, for now, BRL only: it is the one currency the
     * payment gateway charges in, and a price nobody can be charged at
     * serves no one.
     */
    public static String currency(String currency) {
        String code = currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
        boolean known = Currency.getAvailableCurrencies().stream()
                .anyMatch(candidate -> candidate.getCurrencyCode().equals(code));
        if (!known) {
            throw new InvalidPlanException("currency must be an ISO 4217 code, got '%s'".formatted(code));
        }
        if (!SUPPORTED_CURRENCY.equals(code)) {
            throw new InvalidPlanException(
                    "currency must be %s, the one the payment gateway charges in, got '%s'".formatted(SUPPORTED_CURRENCY, code));
        }
        return code;
    }

    public static BillingInterval interval(BillingInterval interval) {
        if (interval == null) {
            throw new InvalidPlanException("interval is required");
        }
        return interval;
    }

    private static String bounded(String value, String field, int maxLength) {
        if (value.length() > maxLength) {
            throw new InvalidPlanException(
                    "%s must be at most %d characters, got %d".formatted(field, maxLength, value.length()));
        }
        return value;
    }
}
