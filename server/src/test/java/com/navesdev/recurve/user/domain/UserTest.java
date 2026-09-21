package com.navesdev.recurve.user.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.navesdev.recurve.user.domain.exception.InvalidUserException;
import com.navesdev.recurve.user.domain.exception.UserAlreadyInactiveException;

/** The rules an operator obeys, stated as FR-01 and BR-01/BR-02 state them. */
class UserTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    @Nested
    @DisplayName("FR-01.1 registering an operator")
    class Registering {

        @Test
        void anOperatorIsUsableAsSoonAsItIsRegistered() {
            User operator = newOperator(Set.of(Permission.VIEW_PLANS));

            assertThat(operator.isActive()).isTrue();
        }

        @Test
        void anOperatorMustHaveANameAnEmailAndAPassword() {
            // The rules are UserValidator's; this shows the entity cannot
            // be built around them.
            assertThatThrownBy(() -> User.create(" ", "ada@recurve.local", "hash", Set.of(), NOW))
                    .isInstanceOf(InvalidUserException.class);
            assertThatThrownBy(() -> User.create("Ada", " ", "hash", Set.of(), NOW))
                    .isInstanceOf(InvalidUserException.class);
            assertThatThrownBy(() -> User.create("Ada", "ada@recurve.local", " ", Set.of(), NOW))
                    .isInstanceOf(InvalidUserException.class);
            assertThatThrownBy(() -> User.create("Ada", "not-an-email", "hash", Set.of(), NOW))
                    .isInstanceOf(InvalidUserException.class);
        }

        @Test
        void anOperatorMayBeRegisteredWithNoPermissionAtAll() {
            User operator = newOperator(Set.of());

            assertThat(operator.authorities()).isEmpty();
        }
    }

    @Nested
    @DisplayName("BR-02 the operator email is unique")
    class UniqueEmail {

        @Test
        void twoOperatorsCannotDifferOnlyByTheCaseOfTheirEmail() {
            User lower = User.create("Ada", "ada@recurve.local", "hash", Set.of(), NOW);
            User upper = User.create("Ada", "ADA@Recurve.Local", "hash", Set.of(), NOW);

            assertThat(upper.getEmail()).isEqualTo(lower.getEmail());
        }

        @Test
        void surroundingSpaceIsNotPartOfTheEmail() {
            User operator = User.create("Ada", "  ada@recurve.local  ", "hash", Set.of(), NOW);

            assertThat(operator.getEmail()).isEqualTo("ada@recurve.local");
        }
    }

    @Nested
    @DisplayName("BR-01 manage implies view")
    class ManageImpliesView {

        @Test
        void managingAResourceGrantsTheRightToViewIt() {
            User operator = newOperator(Set.of(Permission.MANAGE_PLANS));

            assertThat(operator.authorities())
                    .containsExactlyInAnyOrder(Permission.MANAGE_PLANS, Permission.VIEW_PLANS);
        }

        @Test
        void theImplicationDoesNotReachAnotherResource() {
            User operator = newOperator(Set.of(Permission.MANAGE_PLANS));

            assertThat(operator.authorities()).doesNotContain(Permission.VIEW_SUBSCRIBERS);
        }

        @Test
        void viewingAResourceDoesNotGrantTheRightToManageIt() {
            User operator = newOperator(Set.of(Permission.VIEW_PAYMENTS));

            assertThat(operator.authorities()).containsExactly(Permission.VIEW_PAYMENTS);
        }

        @Test
        void whatWasGrantedIsReportedAsGranted() {
            User operator = newOperator(Set.of(Permission.MANAGE_PLANS));

            assertThat(operator.getPermissions()).containsExactly(Permission.MANAGE_PLANS);
        }
    }

    @Nested
    @DisplayName("FR-01.2 granting and revoking permissions")
    class ChangingPermissions {

        @Test
        void anOperatorMayBeGivenADifferentSetOfPermissions() {
            User operator = newOperator(Set.of(Permission.MANAGE_USERS));

            operator.replacePermissions(Set.of(Permission.VIEW_PLANS));

            assertThat(operator.getPermissions()).containsExactly(Permission.VIEW_PLANS);
        }

        @Test
        void revokingEveryPermissionLeavesAnOperatorWhoCanDoNothing() {
            User operator = newOperator(Set.of(Permission.MANAGE_USERS));

            operator.replacePermissions(Set.of());

            assertThat(operator.authorities()).isEmpty();
        }
    }

    @Nested
    @DisplayName("FR-01.3 an operator is deactivated, never deleted")
    class Deactivating {

        @Test
        void aDeactivatedOperatorKeepsItsIdentityAndItsPermissions() {
            User operator = newOperator(Set.of(Permission.MANAGE_USERS));

            operator.deactivate();

            assertThat(operator.isActive()).isFalse();
            assertThat(operator.getEmail()).isEqualTo("ada@recurve.local");
            assertThat(operator.getPermissions()).containsExactly(Permission.MANAGE_USERS);
        }

        @Test
        void deactivatingAnAlreadyInactiveOperatorIsRefused() {
            User operator = newOperator(Set.of());
            operator.deactivate();

            assertThatThrownBy(operator::deactivate).isInstanceOf(UserAlreadyInactiveException.class);
        }
    }

    private static User newOperator(Set<Permission> permissions) {
        return User.create("Ada", "ada@recurve.local", "hash", permissions, NOW);
    }
}
