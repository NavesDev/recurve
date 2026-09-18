package com.navesdev.recurve.shared.controller;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

/**
 * Offset-based page envelope (FR-07.2): the page items plus the page
 * number, the page size and the total count after search and filter.
 */
public record PageResponse<T>(List<T> items, int page, int size, long total) {

    public static <E, T> PageResponse<T> from(Page<E> source, Function<E, T> mapper) {
        return new PageResponse<>(
                source.getContent().stream().map(mapper).toList(),
                source.getNumber(),
                source.getSize(),
                source.getTotalElements());
    }
}
