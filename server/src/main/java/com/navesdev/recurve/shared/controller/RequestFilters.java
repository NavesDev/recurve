package com.navesdev.recurve.shared.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses the repeatable {@code filter} query parameter shared by every
 * listing (FR-06).
 *
 * <pre>
 * ?filter=active:true                       one criterion
 * ?filter=status:ACTIVE,PAST_DUE            one criterion, two values
 * ?filter=status:ACTIVE&amp;filter=plan:&lt;id&gt;    two criteria
 * </pre>
 *
 * Values of one criterion combine with OR, criteria with AND, which is the
 * semantics FR-06 fixes — deliberately not an expression language. Which
 * fields exist is decided per feature by its own allow-list, not here; this
 * only turns text into field and values.
 */
public final class RequestFilters {

    private RequestFilters() {
    }

    public static Map<String, List<String>> parse(List<String> criteria) {
        Map<String, List<String>> parsed = new LinkedHashMap<>();

        if (criteria == null) {
            return parsed;
        }

        for (String criterion : criteria) {
            int separator = criterion.indexOf(':');
            if (separator < 1 || separator == criterion.length() - 1) {
                throw new InvalidRequestException(
                        "filter must be written as field:value, got '%s'".formatted(criterion));
            }

            String field = criterion.substring(0, separator).trim();
            List<String> values = new ArrayList<>();

            for (String value : criterion.substring(separator + 1).split(",", -1)) {
                if (value.isBlank()) {
                    throw new InvalidRequestException(
                            "filter '%s' has an empty value".formatted(field));
                }
                values.add(value.trim());
            }

            // Repeating a field is the same as listing its values together.
            parsed.computeIfAbsent(field, key -> new ArrayList<>()).addAll(values);
        }

        return parsed;
    }
}
