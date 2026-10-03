package com.navesdev.recurve.auth.controller;

import java.util.Set;
import java.util.UUID;

import com.navesdev.recurve.user.domain.Permission;
import com.navesdev.recurve.user.domain.User;

/**
 * FR-05.4: who is signed in and what they may do, with BR-01 already
 * applied — the client checks one permission, never an implication.
 */
public record MeResponse(UUID id, String name, String email, Set<Permission> permissions) {

    public static MeResponse from(User operator) {
        return new MeResponse(operator.getId(), operator.getName(), operator.getEmail(), operator.authorities());
    }
}
