package com.navesdev.recurve.user.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.navesdev.recurve.user.domain.exception.UserAlreadyInactiveException;

class UserTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    @Test
    void createsAnActiveOperator() {
        User user = newUser(Set.of(Permission.VIEW_PLANS));

        assertThat(user.getId()).isNotNull();
        assertThat(user.getName()).isEqualTo("Ada");
        assertThat(user.isActive()).isTrue();
        assertThat(user.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void normalizesEmailSoUniquenessIsNotCaseSensitive() {
        User user = User.create("Ada", "  Ada@Recurve.LOCAL ", "hash", Set.of(), NOW);

        assertThat(user.getEmail()).isEqualTo("ada@recurve.local");
    }

    @Test
    void rejectsBlankRequiredValues() {
        assertThatThrownBy(() -> User.create(" ", "ada@recurve.local", "hash", Set.of(), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void expandsManageIntoTheMatchingViewAuthority() {
        User user = newUser(Set.of(Permission.MANAGE_USERS));

        assertThat(user.authorities())
                .containsExactlyInAnyOrder(Permission.MANAGE_USERS, Permission.VIEW_USERS);
    }

    @Test
    void keepsGrantedPermissionsSeparateFromExpandedAuthorities() {
        User user = newUser(Set.of(Permission.MANAGE_PAYMENTS));

        assertThat(user.getPermissions()).containsExactly(Permission.MANAGE_PAYMENTS);
        assertThat(user.authorities()).hasSize(2);
    }

    @Test
    void replacesPermissionsWholesale() {
        User user = newUser(Set.of(Permission.MANAGE_USERS));

        user.replacePermissions(Set.of(Permission.VIEW_PLANS));

        assertThat(user.getPermissions()).containsExactly(Permission.VIEW_PLANS);
    }

    @Test
    void deactivates() {
        User user = newUser(Set.of());

        user.deactivate();

        assertThat(user.isActive()).isFalse();
    }

    @Test
    void refusesToDeactivateTwice() {
        User user = newUser(Set.of());
        user.deactivate();

        assertThatThrownBy(user::deactivate).isInstanceOf(UserAlreadyInactiveException.class);
    }

    @Test
    void doesNotExposeItsPermissionsForMutation() {
        User user = newUser(Set.of(Permission.VIEW_USERS));

        assertThatThrownBy(() -> user.getPermissions().add(Permission.MANAGE_USERS))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static User newUser(Set<Permission> permissions) {
        return User.create("Ada", "ada@recurve.local", "hash", permissions, NOW);
    }
}
