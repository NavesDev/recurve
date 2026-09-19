package com.navesdev.recurve.user.controller;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.navesdev.recurve.shared.controller.ListingRequest;
import com.navesdev.recurve.shared.controller.ListingRequests;
import com.navesdev.recurve.shared.controller.PageResponse;
import com.navesdev.recurve.user.domain.User;
import com.navesdev.recurve.user.service.UserService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Converts HTTP into a service call and the result into a response. Holds
 * no rule. Which permission each route needs is decided in
 * {@code SecurityConfig}, before a request reaches here.
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    /** Text fields sort on their keyword copy; the contract names it as the index does. */
    private static final String DEFAULT_SORT = "name.keyword";

    private final UserService service;
    private final ListingRequests listings;

    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        User created = service.create(request.toCommand());

        return ResponseEntity
                .created(URI.create("/api/users/" + created.getId()))
                .body(UserResponse.from(created));
    }

    @PutMapping("/{id}")
    public UserResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        return UserResponse.from(service.update(request.toCommand(id)));
    }

    /** FR-01.3: deactivates the operator; the record is kept. */
    @DeleteMapping("/{id}")
    public UserResponse deactivate(@PathVariable UUID id) {
        return UserResponse.from(service.deactivate(id));
    }

    @GetMapping("/{id}")
    public UserResponse findById(@PathVariable UUID id) {
        return UserResponse.from(service.findById(id));
    }

    /** FR-01.5: rebuilds the search index from the database. */
    @PostMapping("/reindex")
    public ReindexResponse reindex() {
        return new ReindexResponse(service.reindex());
    }

    /**
     * FR-06.1 and FR-07. {@code q} searches name and email by word prefix,
     * case-insensitively. {@code filter} is repeatable and written as
     * {@code field:value} or {@code field:value1,value2} — values of one
     * field combine with OR, separate filters with AND. {@code sort} is
     * written as Elasticsearch's own URL takes it, {@code field:desc} with
     * several keys separated by commas, and defaults to {@code name.keyword}
     * ascending.
     *
     * <p>Field names and values go to Elasticsearch as written. The index
     * mapping decides which fields can be filtered or sorted on; a field it
     * closes comes back as a 400 through {@code GlobalExceptionHandler},
     * and a filter on a field it does not know matches nothing.
     */
    @GetMapping
    public PageResponse<UserResponse> search(
            @RequestParam(required = false) String q,
            HttpServletRequest request,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {

        ListingRequest listing = listings.parse(
                q, request.getParameterValues("filter"), page, size, sort, DEFAULT_SORT);

        return PageResponse.from(service.search(listing.filter(), listing.pageable()), UserResponse::from);
    }
}
