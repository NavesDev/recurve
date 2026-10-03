package com.navesdev.recurve.plan.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.navesdev.recurve.plan.domain.Plan;
import com.navesdev.recurve.plan.service.PlanService;
import com.navesdev.recurve.shared.controller.Listing;
import com.navesdev.recurve.shared.controller.ListingRequest;
import com.navesdev.recurve.shared.controller.PageResponse;
import com.navesdev.recurve.shared.controller.ReindexResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Converts HTTP into a service call and the result into a response. Holds
 * no rule. A price is created here, under its plan, because it needs one;
 * once it exists it is addressed alone, in {@link PriceController}.
 */
@RestController
@RequestMapping("/api/plans")
@RequiredArgsConstructor
public class PlanController {

    /** Text fields sort on their keyword copy; the contract names it as the index does. */
    private static final String DEFAULT_SORT = "name.keyword";

    private final PlanService service;

    @PostMapping
    public ResponseEntity<PlanResponse> create(@Valid @RequestBody CreatePlanRequest request) {
        Plan created = service.create(request.toCommand());

        return ResponseEntity
                .created(URI.create("/api/plans/" + created.getId()))
                .body(PlanResponse.from(created));
    }

    @PutMapping("/{id}")
    public PlanResponse update(@PathVariable UUID id, @Valid @RequestBody UpdatePlanRequest request) {
        return PlanResponse.from(service.update(request.toCommand(id)));
    }

    /** FR-02.4: deactivates the plan; the record and its prices are kept. */
    @DeleteMapping("/{id}")
    public PlanResponse deactivate(@PathVariable UUID id) {
        return PlanResponse.from(service.deactivate(id));
    }

    @GetMapping("/{id}")
    public PlanResponse findById(@PathVariable UUID id) {
        return PlanResponse.from(service.findById(id));
    }

    /**
     * FR-02.2. Answers with the whole plan and no {@code Location}: a
     * price has no address of its own to read.
     */
    @PostMapping("/{id}/prices")
    public ResponseEntity<PlanResponse> addPrice(@PathVariable UUID id, @Valid @RequestBody AddPriceRequest request) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(PlanResponse.from(service.addPrice(request.toCommand(id))));
    }

    /** Rebuilds the plan search index from the database. */
    @PostMapping("/reindex")
    public ReindexResponse reindex() {
        return new ReindexResponse(service.reindex());
    }

    /**
     * FR-06.2 and FR-07. {@code q} searches the name by word prefix;
     * {@code filter=activeIntervals:MONTHLY} keeps plans with an active
     * monthly price; {@code filter=active:true} keeps active plans. The
     * index mapping decides what can be filtered or sorted on.
     */
    @GetMapping
    public PageResponse<PlanResponse> search(@Listing(defaultSort = DEFAULT_SORT) ListingRequest listing) {
        return PageResponse.from(service.search(listing.filter(), listing.pageable()), PlanResponse::from);
    }
}
