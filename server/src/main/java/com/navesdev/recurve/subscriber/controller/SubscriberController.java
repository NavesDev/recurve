package com.navesdev.recurve.subscriber.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.navesdev.recurve.shared.controller.Listing;
import com.navesdev.recurve.shared.controller.ListingRequest;
import com.navesdev.recurve.shared.controller.PageResponse;
import com.navesdev.recurve.shared.controller.ReindexResponse;
import com.navesdev.recurve.subscriber.domain.SubscriberSummary;
import com.navesdev.recurve.subscriber.service.SubscriberService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Converts HTTP into a service call and the result into a response. Holds no rule. */
@RestController
@RequestMapping("/api/subscribers")
@RequiredArgsConstructor
public class SubscriberController {

    /** FR-06.3: newest subscriber first. */
    private static final String DEFAULT_SORT = "startedAt:desc";

    private final SubscriberService service;

    @PostMapping
    public ResponseEntity<SubscriberResponse> create(@Valid @RequestBody CreateSubscriberRequest request) {
        SubscriberSummary created = service.create(request.toCommand());

        return ResponseEntity
                .created(URI.create("/api/subscribers/" + created.id()))
                .body(SubscriberResponse.from(created));
    }

    @PutMapping("/{id}")
    public SubscriberResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateSubscriberRequest request) {
        return SubscriberResponse.from(service.update(request.toCommand(id)));
    }

    /** FR-03.3: cancels the subscriber; the record is kept. */
    @DeleteMapping("/{id}")
    public SubscriberResponse cancel(@PathVariable UUID id) {
        return SubscriberResponse.from(service.cancel(id));
    }

    @GetMapping("/{id}")
    public SubscriberResponse findById(@PathVariable UUID id) {
        return SubscriberResponse.from(service.findById(id));
    }

    /** Rebuilds the subscriber search index from the database. */
    @PostMapping("/reindex")
    public ReindexResponse reindex() {
        return new ReindexResponse(service.reindex());
    }

    /**
     * FR-06.3 and FR-07. {@code q} searches name and email by word prefix;
     * {@code filter=status:ACTIVE,PAST_DUE} and {@code filter=planId:<id>}
     * narrow it; {@code sort=price} orders by the amount billed. The index
     * mapping decides what can be filtered or sorted on.
     */
    @GetMapping
    public PageResponse<SubscriberResponse> search(@Listing(defaultSort = DEFAULT_SORT) ListingRequest listing) {
        return PageResponse.from(service.search(listing.filter(), listing.pageable()), SubscriberResponse::from);
    }
}
