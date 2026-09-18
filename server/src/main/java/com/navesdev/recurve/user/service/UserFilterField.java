package com.navesdev.recurve.user.service;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The fields a listing of operators may be filtered by (FR-06.1).
 *
 * <p>An allow-list, for the same reason {@link UserSortField} is one: the
 * filter parameter is public input, and without it a client could filter
 * over any mapped column — {@code passwordHash} included — turning the
 * listing into an oracle. A field the use case does not offer is a
 * validation error, not an empty result.
 *
 * <p>Adding a filter is a constant here plus a case in the feature's
 * specifications. The endpoint signature does not change.
 */
public enum UserFilterField {

    /** FR-01.3: separates active operators from deactivated ones. */
    ACTIVE("active") {
        @Override
        public void validate(List<String> values) {
            values.stream()
                    .filter(value -> !value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false"))
                    .findFirst()
                    .ifPresent(value -> {
                        throw new IllegalArgumentException(
                                "filter 'active' accepts true or false, got '%s'".formatted(value));
                    });
        }
    };

    private final String field;

    UserFilterField(String field) {
        this.field = field;
    }

    public String field() {
        return field;
    }

    /** Rejects a value this field cannot mean, at the edge, before any query. */
    public abstract void validate(List<String> values);

    public static Optional<UserFilterField> from(String value) {
        return Arrays.stream(values())
                .filter(candidate -> candidate.field.equalsIgnoreCase(value))
                .findFirst();
    }

    public static String allowed() {
        return Arrays.stream(values())
                .map(UserFilterField::field)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }
}
