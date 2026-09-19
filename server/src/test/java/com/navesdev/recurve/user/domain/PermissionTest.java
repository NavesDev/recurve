package com.navesdev.recurve.user.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PermissionTest {

    @Nested
    @DisplayName("BR-01 manage implies view")
    class ManageImpliesView {

        @Test
        void managingAResourceGrantsViewingIt() {
            assertThat(Permission.MANAGE_USERS.expand())
                    .containsExactlyInAnyOrder(Permission.MANAGE_USERS, Permission.VIEW_USERS);
        }

        @Test
        void viewingGrantsNothingElse() {
            assertThat(Permission.VIEW_USERS.expand()).containsExactly(Permission.VIEW_USERS);
        }

        @Test
        void managingTheSystemHasNoViewCounterpartAndGrantsNothingElse() {
            assertThat(Permission.MANAGE_SYSTEM.expand()).containsExactly(Permission.MANAGE_SYSTEM);
        }
    }
}
