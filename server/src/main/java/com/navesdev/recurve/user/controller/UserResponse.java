package com.navesdev.recurve.user.controller;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.navesdev.recurve.user.domain.Permission;
import com.navesdev.recurve.user.domain.User;
import com.navesdev.recurve.user.domain.UserSummary;

/**
 * Reads entity getters only, never a business method. The password hash is
 * deliberately absent.
 */
public record UserResponse(
        UUID id,
        String name,
        String email,
        Set<Permission> permissions,
        boolean active,
        Instant createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getPermissions(),
                user.isActive(),
                user.getCreatedAt());
    }

    /** The listing (FR-06) answers from the read model, not the entity. */
    public static UserResponse from(UserSummary summary) {
        return new UserResponse(
                summary.id(),
                summary.name(),
                summary.email(),
                summary.permissions(),
                summary.active(),
                summary.createdAt());
    }
}
