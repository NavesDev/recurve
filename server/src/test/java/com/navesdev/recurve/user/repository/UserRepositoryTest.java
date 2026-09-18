package com.navesdev.recurve.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import com.navesdev.recurve.user.domain.Permission;
import com.navesdev.recurve.user.domain.User;

/**
 * Runs against a real PostgreSQL, on the schema the Flyway migrations
 * produced. Each test rolls back.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    @Autowired
    private UserRepository repository;

    @BeforeEach
    void setUp() {
        // Hibernate flushes inserts before deletes, so the delete has to
        // be issued before the fixtures or it collides on the unique email.
        repository.deleteAll();
        repository.flush();
        repository.saveAll(List.of(
                User.create("Ada Lovelace", "ada@recurve.local", "hash", Set.of(Permission.MANAGE_USERS), NOW),
                User.create("Grace Hopper", "grace@recurve.local", "hash", Set.of(Permission.VIEW_PLANS), NOW),
                User.create("Alan Turing", "alan@example.com", "hash", Set.of(), NOW)));
        repository.flush();
    }

    @Test
    void persistsPermissionsInTheirOwnTable() {
        User ada = repository.findByEmail("ada@recurve.local").orElseThrow();

        assertThat(ada.getPermissions()).containsExactly(Permission.MANAGE_USERS);
    }

    @Test
    void findsByEmailForLogin() {
        assertThat(repository.findByEmail("grace@recurve.local")).isPresent();
        assertThat(repository.findByEmail("nobody@recurve.local")).isEmpty();
    }

    @Test
    void checksEmailUniquenessIgnoringOneOperator() {
        User ada = repository.findByEmail("ada@recurve.local").orElseThrow();

        assertThat(repository.existsByEmail("ada@recurve.local")).isTrue();
        assertThat(repository.existsByEmailAndIdNot("ada@recurve.local", ada.getId())).isFalse();
        assertThat(repository.existsByEmailAndIdNot("grace@recurve.local", ada.getId())).isTrue();
    }

    @Test
    void searchesNameAndEmailByCaseInsensitiveSubstring() {
        Page<User> byName = search(UserSpecifications.matchesText("LOVE"));
        Page<User> byEmail = search(UserSpecifications.matchesText("example.com"));

        assertThat(byName).extracting(User::getName).containsExactly("Ada Lovelace");
        assertThat(byEmail).extracting(User::getName).containsExactly("Alan Turing");
    }

    @Test
    void filtersByActive() {
        User alan = repository.findByEmail("alan@example.com").orElseThrow();
        alan.deactivate();
        repository.saveAndFlush(alan);

        assertThat(search(UserSpecifications.hasActive(true))).hasSize(2);
        assertThat(search(UserSpecifications.hasActive(false))).hasSize(1);
    }

    @Test
    void combinesSearchAndFilterWithAnd() {
        User ada = repository.findByEmail("ada@recurve.local").orElseThrow();
        ada.deactivate();
        repository.saveAndFlush(ada);

        Specification<User> specification = Specification.allOf(
                UserSpecifications.matchesText("recurve.local"),
                UserSpecifications.hasActive(true));

        assertThat(search(specification)).extracting(User::getName).containsExactly("Grace Hopper");
    }

    @Test
    void paginatesWithTheTotalCountedBeforePaging() {
        Page<User> firstPage = repository.findAll(
                UserSpecifications.matchesText(null),
                PageRequest.of(0, 2, Sort.by("name").ascending()));

        assertThat(firstPage.getContent()).extracting(User::getName)
                .containsExactly("Ada Lovelace", "Alan Turing");
        assertThat(firstPage.getTotalElements()).isEqualTo(3);
    }

    @Test
    void returnsAnEmptyPageBeyondTheEndWithTheCorrectTotal() {
        Page<User> page = repository.findAll(
                UserSpecifications.matchesText(null),
                PageRequest.of(50, 20, Sort.by("name")));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    private Page<User> search(Specification<User> specification) {
        return repository.findAll(specification, PageRequest.of(0, 20, Sort.by("name")));
    }
}
