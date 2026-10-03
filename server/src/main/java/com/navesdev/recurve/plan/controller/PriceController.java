package com.navesdev.recurve.plan.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.navesdev.recurve.plan.service.PlanService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * A price that exists, addressed by its own id: unique across plans, so a
 * plan id beside it would only be a second thing to get wrong. Both routes
 * answer with the plan the price belongs to.
 */
@RestController
@RequestMapping("/api/prices")
@RequiredArgsConstructor
public class PriceController {

    private final PlanService service;

    /**
     * FR-02.7, BR-04: a {@code POST}, not a {@code PUT}. The price is not
     * edited — a successor is created and this one deactivated, so the
     * result is a new resource.
     */
    @PostMapping("/{id}/replace")
    public ResponseEntity<PlanResponse> replace(@PathVariable UUID id, @Valid @RequestBody ReplacePriceRequest request) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(PlanResponse.from(service.replacePrice(request.toCommand(id))));
    }

    /** FR-02.3: deactivates the price; subscribers on it stay. */
    @DeleteMapping("/{id}")
    public PlanResponse deactivate(@PathVariable UUID id) {
        return PlanResponse.from(service.deactivatePrice(id));
    }
}
