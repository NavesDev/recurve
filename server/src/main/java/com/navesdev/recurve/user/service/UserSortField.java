package com.navesdev.recurve.user.service;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The fields a listing of operators may be sorted by (FR-06.1). Anything
 * outside this list is a validation error, so the allowed set is named
 * here rather than passed through as a free string.
 */
public enum UserSortField {

    NAME("name"),
    EMAIL("email"),
    CREATED_AT("createdAt");

    private final String property;

    UserSortField(String property) {
        this.property = property;
    }

    public String property() {
        return property;
    }

    public static Optional<UserSortField> from(String value) {
        return Arrays.stream(values())
                .filter(field -> field.property.equalsIgnoreCase(value)
                        || field.name().equalsIgnoreCase(value))
                .findFirst();
    }

    public static String allowed() {
        return Arrays.stream(values())
                .map(field -> field.property)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }

    public static UserSortField defaultField() {
        return NAME;
    }

    @Override
    public String toString() {
        return property.toLowerCase(Locale.ROOT);
    }
}
