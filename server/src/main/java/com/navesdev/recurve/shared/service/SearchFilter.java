package com.navesdev.recurve.shared.service;

import java.util.List;
import java.util.Map;

/**
 * What a listing asks for (FR-06.1): free text over the feature's
 * searchable fields, plus zero or more criteria, each a field name and
 * the values it may take.
 *
 * <p>Field names are carried as the client wrote them. Which fields can
 * actually be filtered on is decided by the feature's index mapping, not
 * here: a field the mapping closes is refused by Elasticsearch, and one
 * it does not know matches nothing. Values of one field combine with OR,
 * fields with AND.
 */
public record SearchFilter(String text, Map<String, List<String>> criteria) {

    public SearchFilter {
        criteria = criteria == null ? Map.of() : Map.copyOf(criteria);
    }

    public static SearchFilter of(String text) {
        return new SearchFilter(text, Map.of());
    }

    public List<String> valuesOf(String field) {
        return criteria.getOrDefault(field, List.of());
    }
}
