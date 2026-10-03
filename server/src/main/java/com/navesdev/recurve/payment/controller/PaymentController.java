package com.navesdev.recurve.payment.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.navesdev.recurve.payment.domain.PaymentSummary;
import com.navesdev.recurve.payment.service.PaymentService;
import com.navesdev.recurve.shared.controller.Listing;
import com.navesdev.recurve.shared.controller.ListingRequest;
import com.navesdev.recurve.shared.controller.PageResponse;
import com.navesdev.recurve.shared.controller.ReindexResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Converts HTTP into a service call and the result into a response. Holds
 * no rule. Actions on a charge are {@code POST}s to a verb under it: none
 * of them is an edit of the charge's fields.
 */
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    /** FR-04.6: the latest cycle first. */
    private static final String DEFAULT_SORT = "dueAt:desc";

    private final PaymentService service;

    /** FR-04.1: charges the subscriber's current cycle and sends it to the gateway. */
    @PostMapping
    public ResponseEntity<PaymentResponse> request(@Valid @RequestBody RequestPaymentRequest request) {
        PaymentSummary requested = service.request(request.toCommand());

        return ResponseEntity
                .created(URI.create("/api/payments/" + requested.id()))
                .body(PaymentResponse.from(requested));
    }

    /** FR-04.7: retries sending a charge the gateway did not take. */
    @PostMapping("/{id}/send")
    public PaymentResponse send(@PathVariable UUID id) {
        return PaymentResponse.from(service.send(id));
    }

    /** FR-04.5: the customer paid outside the gateway. */
    @PostMapping("/{id}/confirm")
    public PaymentResponse confirm(@PathVariable UUID id) {
        return PaymentResponse.from(service.confirm(id));
    }

    /** FR-04.4. */
    @PostMapping("/{id}/refund")
    public PaymentResponse refund(@PathVariable UUID id) {
        return PaymentResponse.from(service.refund(id));
    }

    /** FR-04.7: asks the gateway where the charge stands, for when its webhook did not arrive. */
    @PostMapping("/{id}/sync")
    public PaymentResponse sync(@PathVariable UUID id) {
        return PaymentResponse.from(service.sync(id));
    }

    @GetMapping("/{id}")
    public PaymentResponse findById(@PathVariable UUID id) {
        return PaymentResponse.from(service.findById(id));
    }

    /** Rebuilds the payment search index from the database. */
    @PostMapping("/reindex")
    public ReindexResponse reindex() {
        return new ReindexResponse(service.reindex());
    }

    /**
     * FR-04.6 and FR-07. {@code filter=subscriberId:<id>} and
     * {@code filter=status:PAID,FAILED} narrow it; {@code q} finds a charge
     * by the gateway's id.
     */
    @GetMapping
    public PageResponse<PaymentResponse> search(@Listing(defaultSort = DEFAULT_SORT) ListingRequest listing) {
        return PageResponse.from(service.search(listing.filter(), listing.pageable()), PaymentResponse::from);
    }
}
