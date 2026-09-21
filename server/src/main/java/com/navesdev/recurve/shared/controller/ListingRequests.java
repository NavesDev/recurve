package com.navesdev.recurve.shared.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import com.navesdev.recurve.shared.service.SearchFilter;

/**
 * Parses the query parameters every listing takes (FR-06, FR-07), once
 * for all of them: {@code q}, the repeatable {@code filter}, {@code page},
 * {@code size} and {@code sort}. A controller hands them over as they
 * arrived and gets back a {@link ListingRequest}.
 *
 * <p>Only syntax and the API's own bounds are checked here. Which fields
 * exist, and what they accept, is the feature's index mapping's to say,
 * and Elasticsearch answers for it.
 */
@Component
public class ListingRequests {

    /**
     * FR-07.4: the secondary sort key that keeps paging stable, so a
     * record never repeats or vanishes between pages. Every read model has
     * an {@code id}, and it stays ascending whichever way the caller asked:
     * it is there to keep pages from overlapping, not to follow the request.
     */
    private static final Sort STABLE_PAGING = Sort.by(Sort.Direction.ASC, "id");

    /** FR-07: the largest page any listing serves; the API's rule, not the index's. */
    private final int maxPageSize;

    public ListingRequests(@Value("${recurve.listing.max-page-size:100}") int maxPageSize) {
        if (maxPageSize < 1) {
            throw new IllegalArgumentException("recurve.listing.max-page-size must be at least 1, got " + maxPageSize);
        }
        this.maxPageSize = maxPageSize;
    }

    /**
     * @param filters the raw values of {@code filter}, straight off the
     *        request ({@code null} when absent); Spring must not bind them,
     *        or it splits on the comma that separates a criterion's values
     * @param defaultSort the field the feature lists by when {@code sort}
     *        is absent, named as its index names it
     */
    public ListingRequest parse(String q, String[] filters, int page, int size, String sort, String defaultSort) {
        SearchFilter filter = new SearchFilter(q,
                RequestFilters.parse(filters == null ? List.of() : List.of(filters)));

        return new ListingRequest(filter, PageRequest.of(validPage(page), validSize(size), sortOf(sort, defaultSort)));
    }

    private static int validPage(int page) {
        if (page < 0) {
            throw new InvalidRequestException("page must be zero or greater");
        }
        return page;
    }

    private int validSize(int size) {
        if (size < 1 || size > maxPageSize) {
            throw new InvalidRequestException("size must be between 1 and " + maxPageSize);
        }
        return size;
    }

    private static Sort sortOf(String sort, String defaultSort) {
        List<Sort.Order> orders = RequestSort.parse(sort, defaultSort).stream()
                .map(key -> new Sort.Order(key.direction(), key.field()))
                .toList();

        return Sort.by(orders).and(STABLE_PAGING);
    }
}
