package com.navesdev.recurve.shared.controller;

import org.springframework.data.domain.Sort;

/**
 * Parses the {@code sort} query parameter shared by every listing (FR-06).
 *
 * <pre>
 * ?sort=email      ascending by email
 * ?sort=-email     descending by email
 * </pre>
 *
 * The direction rides with the field rather than travelling in a parameter
 * of its own, which is what JSON:API, Spring Data, OData and Elasticsearch
 * all do in one spelling or another. A separate direction cannot say what
 * it means once more than one field is sorted on — {@code sort=name&
 * sort=createdAt&direction=desc} names no answer — so keeping them
 * together leaves that door open instead of closing it.
 *
 * <p>Which field names are legal is decided per feature by its own
 * allow-list, not here; this only splits the direction from the field.
 */
public record RequestSort(String field, Sort.Direction direction) {

    private static final char DESCENDING = '-';

    public static RequestSort parse(String value, String defaultField) {
        if (value == null || value.isBlank()) {
            return new RequestSort(defaultField, Sort.Direction.ASC);
        }

        String requested = value.trim();

        if (requested.charAt(0) != DESCENDING) {
            return new RequestSort(requested, Sort.Direction.ASC);
        }

        String field = requested.substring(1).trim();
        if (field.isEmpty()) {
            throw new InvalidRequestException("sort names no field, only a direction");
        }

        return new RequestSort(field, Sort.Direction.DESC);
    }
}
