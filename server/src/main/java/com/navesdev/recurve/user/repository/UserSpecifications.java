package com.navesdev.recurve.user.repository;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.jpa.domain.Specification;

import com.navesdev.recurve.user.domain.User;
import com.navesdev.recurve.user.service.UserFilter;
import com.navesdev.recurve.user.service.UserFilterField;

/**
 * Turns a listing filter into a query (FR-06.1). Knows no business rule:
 * which fields may be filtered is decided by the allow-list in the
 * service, and the values arrive already validated.
 *
 * <p>An absent criterion yields a conjunction rather than null, so the
 * specifications compose without null handling at the call site.
 */
public final class UserSpecifications {

    private UserSpecifications() {
    }

    /** FR-06: search and every criterion must match. */
    public static Specification<User> from(UserFilter filter) {
        List<Specification<User>> specifications = new ArrayList<>();
        specifications.add(matchesText(filter.text()));

        for (UserFilterField field : filter.criteria().keySet()) {
            specifications.add(matches(field, filter.valuesOf(field)));
        }

        return Specification.allOf(specifications);
    }

    /** Free text, case-insensitive, by substring, over name and email. */
    public static Specification<User> matchesText(String text) {
        if (text == null || text.isBlank()) {
            return matchesEverything();
        }

        String pattern = "%" + text.trim().toLowerCase() + "%";

        return (root, query, builder) -> builder.or(
                builder.like(builder.lower(root.get("name")), pattern),
                builder.like(builder.lower(root.get("email")), pattern));
    }

    /** FR-06: several values of one field combine with OR. */
    private static Specification<User> matches(UserFilterField field, List<String> values) {
        if (values.isEmpty()) {
            return matchesEverything();
        }

        return switch (field) {
            case ACTIVE -> anyOf(values.stream()
                    .map(Boolean::parseBoolean)
                    .distinct()
                    .map(UserSpecifications::hasActive)
                    .toList());
        };
    }

    private static Specification<User> hasActive(boolean active) {
        return (root, query, builder) -> builder.equal(root.get("active"), active);
    }

    private static Specification<User> anyOf(List<Specification<User>> alternatives) {
        return alternatives.stream()
                .reduce(Specification::or)
                .orElseGet(UserSpecifications::matchesEverything);
    }

    private static Specification<User> matchesEverything() {
        return (root, query, builder) -> builder.conjunction();
    }
}
