package com.navesdev.recurve.user.domain;

import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;

import com.navesdev.recurve.user.domain.exception.UserAlreadyInactiveException;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * An operator of the system (FR-01). Mutable entity, but state changes only
 * through a business method — there is no public setter. Time always comes
 * in as a parameter; the entity never reads the clock.
 */
@Entity
@Table(name = "users")
@Getter
public class User {

    @Id
    private UUID id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    /**
     * Eager, but fetched by a separate batched select rather than a join:
     * a join would duplicate rows and push pagination into memory.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_permissions", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "permission", nullable = false, length = 40)
    @Enumerated(EnumType.STRING)
    @Fetch(FetchMode.SELECT)
    @BatchSize(size = 100)
    private Set<Permission> permissions = new HashSet<>();

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** For JPA only. */
    protected User() {
    }

    public static User create(String name, String email, String passwordHash,
            Set<Permission> permissions, Instant now) {

        User user = new User();
        user.id = UUID.randomUUID();
        user.name = requireText(name, "name");
        user.email = normalizeEmail(email);
        user.passwordHash = requireText(passwordHash, "passwordHash");
        user.permissions = copyOf(permissions);
        user.active = true;
        user.createdAt = Objects.requireNonNull(now, "now is required");
        return user;
    }

    public void rename(String name) {
        this.name = requireText(name, "name");
    }

    public void changeEmail(String email) {
        this.email = normalizeEmail(email);
    }

    public void changePassword(String passwordHash) {
        this.passwordHash = requireText(passwordHash, "passwordHash");
    }

    public void replacePermissions(Set<Permission> permissions) {
        this.permissions.clear();
        this.permissions.addAll(copyOf(permissions));
    }

    /** FR-01.3: an operator is deactivated, never deleted. */
    public void deactivate() {
        if (!active) {
            throw new UserAlreadyInactiveException(id);
        }
        active = false;
    }

    public void activate() {
        active = true;
    }

    /**
     * The permissions granted, expanded by BR-01 (manage implies view).
     * This is what becomes the principal's authorities at login.
     */
    public Set<Permission> authorities() {
        Set<Permission> expanded = EnumSet.noneOf(Permission.class);
        permissions.forEach(permission -> expanded.addAll(permission.expand()));
        return expanded;
    }

    /** Unmodifiable: the collection changes only through a business method. */
    public Set<Permission> getPermissions() {
        return Set.copyOf(permissions);
    }

    private static Set<Permission> copyOf(Set<Permission> permissions) {
        return permissions == null ? new HashSet<>() : new HashSet<>(permissions);
    }

    private static String normalizeEmail(String email) {
        return requireText(email, "email").toLowerCase();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("%s is required".formatted(field));
        }
        return value.trim();
    }
}
