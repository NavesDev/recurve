package com.navesdev.recurve.shared.controller;

import org.springframework.data.domain.Pageable;

import com.navesdev.recurve.shared.service.SearchFilter;

/**
 * A listing's query parameters, parsed (FR-06, FR-07): what the feature's
 * service takes. Built by {@link ListingRequests}.
 */
public record ListingRequest(SearchFilter filter, Pageable pageable) {
}
