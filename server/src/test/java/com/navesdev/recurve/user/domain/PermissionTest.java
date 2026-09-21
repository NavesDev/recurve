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
            assertThat(Permission.MANAGE_PLANS.expand())
                    .containsExactlyInAnyOrder(Permission.MANAGE_PLANS, Permission.VIEW_PLANS);
        }

        @Test
        void viewingGrantsNothingElse() {
            assertThat(Permission.VIEW_PLANS.expand()).containsExactly(Permission.VIEW_PLANS);
        }

        @Test
        void managingOperatorsHasNoViewCounterpartAndGrantsNothingElse() {
            assertThat(Permission.MANAGE_USERS.expand()).containsExactly(Permission.MANAGE_USERS);
        }

        @Test
        void managingTheSystemHasNoViewCounterpartAndGrantsNothingElse() {
            assertThat(Permission.MANAGE_SYSTEM.expand()).containsExactly(Permission.MANAGE_SYSTEM);
        }
    }
}
