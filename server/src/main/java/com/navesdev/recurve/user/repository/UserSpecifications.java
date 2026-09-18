package com.navesdev.recurve.user.repository;

import org.springframework.data.jpa.domain.Specification;

import com.navesdev.recurve.user.domain.User;

/**
 * Turns a listing filter into a query (FR-06.1). Knows no business rule:
 * which filters exist is decided by the service. An absent criterion
 * yields a conjunction rather than null, so the specifications compose
 * without any null handling at the call site.
 */
public final class UserSpecifications {

    private UserSpecifications() {
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

    public static Specification<User> hasActive(Boolean active) {
        if (active == null) {
            return matchesEverything();
        }
        return (root, query, builder) -> builder.equal(root.get("active"), active);
    }

    private static Specification<User> matchesEverything() {
        return (root, query, builder) -> builder.conjunction();
    }
}
