package com.navesdev.recurve.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import com.navesdev.recurve.user.domain.Permission;
import com.navesdev.recurve.user.domain.User;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * What the JPA repository still answers for on its own — the login
 * lookup (FR-05.1) and the uniqueness checks (BR-02) — against a real
 * PostgreSQL, on the schema the migrations produced. Each test rolls
 * back. The listing rules live in {@code UserSearchRepositoryIT}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserRepositoryIT {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    @Autowired
    private UserRepository repository;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        entityManager.createNativeQuery("TRUNCATE users CASCADE").executeUpdate();
    }

    @Nested
    @DisplayName("FR-05.1 an operator signs in with their email")
    class SigningIn {

        @Test
        void theOperatorIsFoundByTheEmailTheyRegisteredWith() {
            register("Ada Lovelace", "ada@recurve.local", Set.of(Permission.MANAGE_USERS));

            assertThat(repository.findByEmail("ada@recurve.local")).isPresent();
        }

        @Test
        void anEmailNobodyRegisteredFindsNobody() {
            assertThat(repository.findByEmail("nobody@recurve.local")).isEmpty();
        }

        @Test
        void thePermissionsGrantedAtRegistrationAreTheOnesFoundAtSignIn() {
            register("Ada Lovelace", "ada@recurve.local", Set.of(Permission.MANAGE_USERS));
            entityManager.clear();

            User found = repository.findByEmail("ada@recurve.local").orElseThrow();

            assertThat(found.authorities())
                    .containsExactlyInAnyOrder(Permission.MANAGE_USERS, Permission.VIEW_USERS);
        }
    }

    @Nested
    @DisplayName("BR-02 the operator email is unique")
    class UniqueEmail {

        @Test
        void anEmailInUseIsReportedAsInUse() {
            register("Ada Lovelace", "ada@recurve.local", Set.of());

            assertThat(repository.existsByEmail("ada@recurve.local")).isTrue();
            assertThat(repository.existsByEmail("free@recurve.local")).isFalse();
        }

        @Test
        void anOperatorDoesNotClashWithItself() {
            User ada = register("Ada Lovelace", "ada@recurve.local", Set.of());

            assertThat(repository.existsByEmailAndIdNot("ada@recurve.local", ada.getId())).isFalse();
        }

        @Test
        void anotherOperatorHoldingThatEmailIsAClash() {
            User ada = register("Ada Lovelace", "ada@recurve.local", Set.of());
            register("Grace Hopper", "grace@recurve.local", Set.of());

            assertThat(repository.existsByEmailAndIdNot("grace@recurve.local", ada.getId())).isTrue();
        }
    }

    private User register(String name, String email, Set<Permission> permissions) {
        User saved = repository.save(User.create(name, email, "$2a$10$hash", permissions, NOW));
        entityManager.flush();
        return saved;
    }
}
