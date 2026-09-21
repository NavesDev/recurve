package com.navesdev.recurve.user.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Arrays;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** What the listing shows of an operator (FR-01.4), and what it never shows. */
class UserSummaryTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    @Nested
    @DisplayName("FR-01.4 the listing shows an operator with their permissions")
    class Projection {

        @Test
        void everyFieldTheListingShowsComesFromTheOperator() {
            User user = User.create("Ada Lovelace", "ada@recurve.local", "$2a$10$hash",
                    Set.of(Permission.MANAGE_USERS), NOW);

            UserSummary summary = UserSummary.of(user);

            assertThat(summary.id()).isEqualTo(user.getId());
            assertThat(summary.name()).isEqualTo("Ada Lovelace");
            assertThat(summary.email()).isEqualTo("ada@recurve.local");
            assertThat(summary.permissions()).containsExactly(Permission.MANAGE_USERS);
            assertThat(summary.active()).isTrue();
            assertThat(summary.createdAt()).isEqualTo(NOW);
        }

        @Test
        void aDeactivatedOperatorIsSummarizedAsInactive() {
            User user = User.create("Ada Lovelace", "ada@recurve.local", "$2a$10$hash", Set.of(), NOW);
            user.deactivate();

            assertThat(UserSummary.of(user).active()).isFalse();
        }
    }

    @Nested
    @DisplayName("NFR-04 a password never leaves the system")
    class NoPassword {

        @Test
        void theSummaryHasNoPlaceForAPasswordHash() {
            assertThat(Arrays.stream(UserSummary.class.getRecordComponents()).map(c -> c.getName()))
                    .doesNotContain("passwordHash", "password");
        }
    }
}
