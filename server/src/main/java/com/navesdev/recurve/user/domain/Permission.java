package com.navesdev.recurve.user.domain;

import java.util.Set;

/**
 * Granular access. Most permissions come in pairs, one read and one write
 * per resource (BR-01). Two stand alone: {@link #MANAGE_USERS}, because
 * seeing who operates the system without being able to change it serves
 * no one, and {@link #MANAGE_SYSTEM}, an administrative operation with no
 * resource of its own. Access is not modelled as a role: an operator
 * holds exactly the permissions granted to them.
 */
public enum Permission {

    /** Operators and their permissions. No read counterpart: managing them is the only reason to see them. */
    MANAGE_USERS,
    VIEW_PLANS,
    MANAGE_PLANS,
    VIEW_SUBSCRIBERS,
    MANAGE_SUBSCRIBERS,
    VIEW_PAYMENTS,
    MANAGE_PAYMENTS,
    /** Operational routines with no resource of their own: rebuilding a search index. */
    MANAGE_SYSTEM;

    /**
     * BR-01: manage implies view. Expanding here — once, while building the
     * principal's authorities — is what lets every route rule in
     * {@code SecurityConfig} name a single permission instead of repeating
     * the implication at every read route.
     */
    public Set<Permission> expand() {
        return switch (this) {
            case MANAGE_PLANS -> Set.of(MANAGE_PLANS, VIEW_PLANS);
            case MANAGE_SUBSCRIBERS -> Set.of(MANAGE_SUBSCRIBERS, VIEW_SUBSCRIBERS);
            case MANAGE_PAYMENTS -> Set.of(MANAGE_PAYMENTS, VIEW_PAYMENTS);
            default -> Set.of(this);
        };
    }
}
