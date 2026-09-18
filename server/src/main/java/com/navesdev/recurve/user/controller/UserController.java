package com.navesdev.recurve.user.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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

import com.navesdev.recurve.shared.controller.InvalidRequestException;
import com.navesdev.recurve.shared.controller.PageResponse;
import com.navesdev.recurve.user.domain.User;
import com.navesdev.recurve.user.service.UserFilter;
import com.navesdev.recurve.user.service.UserService;
import com.navesdev.recurve.user.service.UserSortField;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Converts HTTP into a service call and the result into a response. Holds
 * no rule and no {@code @PreAuthorize} — authorization lives on the
 * service, so the scheduler and cross-feature calls go through it too.
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private static final int MAX_PAGE_SIZE = 100;

    private final UserService service;

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

    /**
     * FR-06.1 and FR-07. {@code q} searches name and email by substring,
     * case-insensitively; {@code active} filters; {@code sort} is limited
     * to the allowed fields and defaults to name ascending.
     */
    @GetMapping
    public PageResponse<UserResponse> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "asc") String direction) {

        Pageable pageable = PageRequest.of(validPage(page), validSize(size), sortOf(sort, direction));
        Page<User> found = service.search(new UserFilter(q, active), pageable);

        return PageResponse.from(found, UserResponse::from);
    }

    private static int validPage(int page) {
        if (page < 0) {
            throw new InvalidRequestException("page must be zero or greater");
        }
        return page;
    }

    private static int validSize(int size) {
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        return size;
    }

    private static Sort sortOf(String sort, String direction) {
        UserSortField field = sort == null || sort.isBlank()
                ? UserSortField.defaultField()
                : UserSortField.from(sort).orElseThrow(() -> new InvalidRequestException(
                        "sort must be one of: " + UserSortField.allowed()));

        Sort.Direction way = switch (direction.toLowerCase()) {
            case "asc" -> Sort.Direction.ASC;
            case "desc" -> Sort.Direction.DESC;
            default -> throw new InvalidRequestException("direction must be asc or desc");
        };

        // FR-07.4: a fixed secondary key keeps the ordering stable, so an
        // operator never repeats or vanishes between pages.
        return Sort.by(way, field.property()).and(Sort.by(Sort.Direction.ASC, "id"));
    }
}
