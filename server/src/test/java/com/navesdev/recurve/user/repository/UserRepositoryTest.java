package com.navesdev.recurve.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import com.navesdev.recurve.user.domain.Permission;
import com.navesdev.recurve.user.domain.User;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * The listing rules (FR-06, FR-07) against a real PostgreSQL, on the
 * schema the migrations produced. Each test rolls back.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserRepositoryTest {

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

    @Nested
    @DisplayName("FR-06 searching and filtering")
    class SearchingAndFiltering {

        @BeforeEach
        void register() {
            UserRepositoryTest.this.register("Ada Lovelace", "ada@recurve.local", Set.of());
            UserRepositoryTest.this.register("Grace Hopper", "grace@recurve.local", Set.of());
            UserRepositoryTest.this.register("Alan Turing", "alan@example.com", Set.of());
        }

        @Test
        void theSearchMatchesPartOfAName() {
            assertThat(names(search(UserSpecifications.matchesText("Lovelace"))))
                    .containsExactly("Ada Lovelace");
        }

        @Test
        void theSearchMatchesPartOfAnEmail() {
            assertThat(names(search(UserSpecifications.matchesText("example.com"))))
                    .containsExactly("Alan Turing");
        }

        @Test
        void theSearchIgnoresCase() {
            assertThat(names(search(UserSpecifications.matchesText("lovelace"))))
                    .isEqualTo(names(search(UserSpecifications.matchesText("LOVELACE"))));
        }

        @Test
        void theSearchMatchesFromTheMiddleOfTheWord() {
            assertThat(names(search(UserSpecifications.matchesText("urin"))))
                    .containsExactly("Alan Turing");
        }

        @Test
        void anAbsentSearchMatchesEveryone() {
            assertThat(search(UserSpecifications.matchesText(null))).hasSize(3);
            assertThat(search(UserSpecifications.matchesText("  "))).hasSize(3);
        }

        @Test
        void filteringSeparatesActiveFromInactiveOperators() {
            deactivate("alan@example.com");

            assertThat(search(UserSpecifications.hasActive(true))).hasSize(2);
            assertThat(search(UserSpecifications.hasActive(false))).hasSize(1);
        }

        @Test
        void anAbsentFilterMatchesBoth() {
            deactivate("alan@example.com");

            assertThat(search(UserSpecifications.hasActive(null))).hasSize(3);
        }

        @Test
        void aSearchAndAFilterMustBothMatch() {
            deactivate("ada@recurve.local");

            Specification<User> both = Specification.allOf(
                    UserSpecifications.matchesText("recurve.local"),
                    UserSpecifications.hasActive(true));

            assertThat(names(search(both))).containsExactly("Grace Hopper");
        }
    }

    @Nested
    @DisplayName("FR-07 pagination")
    class Pagination {

        @Test
        void theTotalCountsEveryMatchNotJustThePage() {
            registerMany(5, "Operator");

            Page<User> firstPage = page(0, 2, Sort.by("name").ascending());

            assertThat(firstPage.getContent()).hasSize(2);
            assertThat(firstPage.getTotalElements()).isEqualTo(5);
        }

        @Test
        void theTotalCountsWhatMatchedTheSearchNotTheWholeTable() {
            registerMany(5, "Operator");
            register("Ada Lovelace", "ada@recurve.local", Set.of());

            Page<User> found = repository.findAll(
                    UserSpecifications.matchesText("Lovelace"),
                    PageRequest.of(0, 2, Sort.by("name")));

            assertThat(found.getTotalElements()).isEqualTo(1);
        }

        @Test
        void aPageBeyondTheEndIsEmptyButStillReportsTheTotal() {
            registerMany(3, "Operator");

            Page<User> beyond = page(50, 20, Sort.by("name"));

            assertThat(beyond.getContent()).isEmpty();
            assertThat(beyond.getTotalElements()).isEqualTo(3);
        }

        @Test
        void everyOperatorAppearsExactlyOnceWhenPagingThroughTiedNames() {
            // FR-07.4: every operator here sorts identically by name, so
            // only the secondary key keeps the pages from overlapping.
            registerMany(5, "Same Name");

            List<UUID> seen = new ArrayList<>();
            Sort byTiedField = Sort.by("name").ascending().and(Sort.by("id").ascending());
            for (int number = 0; number < 3; number++) {
                page(number, 2, byTiedField).forEach(operator -> seen.add(operator.getId()));
            }

            assertThat(seen).hasSize(5).doesNotHaveDuplicates();
        }
    }

    private Page<User> page(int number, int size, Sort sort) {
        return repository.findAll(UserSpecifications.matchesText(null), PageRequest.of(number, size, sort));
    }

    private Page<User> search(Specification<User> specification) {
        return repository.findAll(specification, PageRequest.of(0, 20, Sort.by("name")));
    }

    private static List<String> names(Page<User> page) {
        return page.getContent().stream().map(User::getName).toList();
    }

    private void registerMany(int howMany, String sharedName) {
        for (int index = 0; index < howMany; index++) {
            register(sharedName, "operator%d@recurve.local".formatted(index), Set.of());
        }
    }

    private User register(String name, String email, Set<Permission> permissions) {
        User saved = repository.save(User.create(name, email, "$2a$10$hash", permissions, NOW));
        entityManager.flush();
        return saved;
    }

    private void deactivate(String email) {
        User operator = repository.findByEmail(email).orElseThrow();
        operator.deactivate();
        repository.save(operator);
        entityManager.flush();
    }
}
