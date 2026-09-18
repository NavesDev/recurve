package com.navesdev.recurve.user.domain;

import java.util.Set;

/**
 * Granular access, one read and one write permission per resource (BR-01).
 * Access is not modelled as a role: an operator holds exactly the
 * permissions granted to them.
 */
public enum Permission {

    VIEW_USERS,
    MANAGE_USERS,
    VIEW_PLANS,
    MANAGE_PLANS,
    VIEW_SUBSCRIBERS,
    MANAGE_SUBSCRIBERS,
    VIEW_PAYMENTS,
    MANAGE_PAYMENTS;

    /**
     * BR-01: manage implies view. Expanding here — once, while building the
     * principal's authorities — is what lets every {@code @PreAuthorize}
     * name a single permission instead of repeating the implication at
     * every read use case.
     */
    public Set<Permission> expand() {
        return switch (this) {
            case MANAGE_USERS -> Set.of(MANAGE_USERS, VIEW_USERS);
            case MANAGE_PLANS -> Set.of(MANAGE_PLANS, VIEW_PLANS);
            case MANAGE_SUBSCRIBERS -> Set.of(MANAGE_SUBSCRIBERS, VIEW_SUBSCRIBERS);
            case MANAGE_PAYMENTS -> Set.of(MANAGE_PAYMENTS, VIEW_PAYMENTS);
            default -> Set.of(this);
        };
    }
}
