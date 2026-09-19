package com.navesdev.recurve.user.service;

import java.util.List;
import java.util.Map;

/**
 * A listing filter (FR-06.1): free text over name and email, plus zero or
 * more criteria, each a field name and the values it may take.
 *
 * <p>The field names are passed through as the client wrote them. Which
 * fields can actually be filtered on is decided by the index mapping
 * ({@code search/users-mapping.json}), not here: a field the mapping
 * closes is refused by Elasticsearch, and one it does not know matches
 * nothing. Values of one field combine with OR, fields with AND.
 */
public record UserFilter(String text, Map<String, List<String>> criteria) {

    public UserFilter {
        criteria = criteria == null ? Map.of() : Map.copyOf(criteria);
    }

    public static UserFilter of(String text) {
        return new UserFilter(text, Map.of());
    }

    public List<String> valuesOf(String field) {
        return criteria.getOrDefault(field, List.of());
    }
}
