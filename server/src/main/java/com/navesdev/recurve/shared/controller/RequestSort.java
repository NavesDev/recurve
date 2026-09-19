package com.navesdev.recurve.shared.controller;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.Sort;

/**
 * Parses the {@code sort} query parameter shared by every listing (FR-06),
 * in the spelling Elasticsearch's own URL takes:
 *
 * <pre>
 * ?sort=email.keyword                     ascending by email
 * ?sort=email.keyword:desc                descending by email
 * ?sort=active:desc,name.keyword:asc      several keys, in order
 * </pre>
 *
 * A key without a direction is ascending, as it is for Elasticsearch. The
 * direction rides with the field rather than travelling in a parameter of
 * its own: a separate direction cannot say what it means once more than
 * one field is sorted on.
 *
 * <p>Which field names are legal is decided by the feature's index
 * mapping, not here; this only splits the direction from the field.
 */
public record RequestSort(String field, Sort.Direction direction) {

    private static final String KEY_SEPARATOR = ",";
    private static final char DIRECTION_SEPARATOR = ':';

    /** The keys as named, in order; the feature's default, ascending, when absent. */
    public static List<RequestSort> parse(String value, String defaultField) {
        if (value == null || value.isBlank()) {
            return List.of(new RequestSort(defaultField, Sort.Direction.ASC));
        }

        List<RequestSort> keys = new ArrayList<>();
        for (String key : value.split(KEY_SEPARATOR, -1)) {
            keys.add(parseKey(key.trim()));
        }
        return keys;
    }

    private static RequestSort parseKey(String key) {
        int separator = key.indexOf(DIRECTION_SEPARATOR);
        String field = (separator < 0 ? key : key.substring(0, separator)).trim();

        if (field.isEmpty()) {
            throw new InvalidRequestException("sort names no field, got '%s'".formatted(key));
        }
        if (separator < 0) {
            return new RequestSort(field, Sort.Direction.ASC);
        }

        String direction = key.substring(separator + 1).trim();
        return new RequestSort(field, switch (direction.toLowerCase()) {
            case "asc" -> Sort.Direction.ASC;
            case "desc" -> Sort.Direction.DESC;
            default -> throw new InvalidRequestException(
                    "sort direction must be asc or desc, got '%s'".formatted(direction));
        });
    }
}
