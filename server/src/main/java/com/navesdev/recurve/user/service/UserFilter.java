package com.navesdev.recurve.user.service;

import java.util.List;
import java.util.Map;

/**
 * A listing filter (FR-06.1): free text over name and email, plus zero or
 * more criteria drawn from {@link UserFilterField}.
 *
 * <p>Criteria arrive already validated against the allow-list, so the
 * persistence layer can map them without asking whether they are legal.
 * Values of one field combine with OR, fields with AND.
 */
public record UserFilter(String text, Map<UserFilterField, List<String>> criteria) {

    public UserFilter {
        criteria = criteria == null ? Map.of() : Map.copyOf(criteria);
    }

    public static UserFilter of(String text) {
        return new UserFilter(text, Map.of());
    }

    public List<String> valuesOf(UserFilterField field) {
        return criteria.getOrDefault(field, List.of());
    }
}
