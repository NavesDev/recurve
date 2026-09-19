package com.navesdev.recurve.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.transaction.annotation.Transactional;

import com.navesdev.recurve.shared.service.SearchFilter;
import com.navesdev.recurve.user.domain.User;
import com.navesdev.recurve.user.domain.UserSummary;
import com.navesdev.recurve.user.repository.UserRepository;
import com.navesdev.recurve.user.repository.UserSearchRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * The index's schema migration: what startup does when the index is
 * missing, and what it leaves alone when it is not.
 */
@SpringBootTest
@Transactional
class UserIndexBootstrapIT {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    @Autowired
    private UserIndexBootstrap bootstrap;

    @Autowired
    private UserRepository repository;

    @Autowired
    private UserSearchRepository searchRepository;

    @Autowired
    private ElasticsearchOperations operations;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        entityManager.createNativeQuery("TRUNCATE users CASCADE").executeUpdate();
        repository.save(User.create("Ada Lovelace", "ada@recurve.local", "$2a$10$hash", Set.of(), NOW));
        repository.save(User.create("Grace Hopper", "grace@recurve.local", "$2a$10$hash", Set.of(), NOW));
        entityManager.flush();
    }

    @Nested
    @DisplayName("A missing index is created and filled from the database")
    class MissingIndex {

        @Test
        void everyOperatorInTheDatabaseIsInTheNewIndex() {
            operations.indexOps(UserSummary.class).delete();

            bootstrap.run(null);

            assertThat(searchRepository.indexExists()).isTrue();
            assertThat(searchRepository.search(SearchFilter.of(null), PageRequest.of(0, 10)).getTotalElements())
                    .isEqualTo(2);
        }

        @Test
        void theNewIndexCarriesTheMappingNotAGuessedOne() {
            operations.indexOps(UserSummary.class).delete();

            bootstrap.run(null);

            // Word-prefix matching only exists with the analyzer from search/users-settings.json.
            assertThat(searchRepository.search(SearchFilter.of("hopp"), PageRequest.of(0, 10)).getTotalElements())
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("An existing index is left alone")
    class ExistingIndex {

        @Test
        void startupDoesNotRebuildAnIndexThatIsAlreadyThere() {
            searchRepository.recreateIndex();
            searchRepository.save(UserSummary.of(repository.findByEmail("ada@recurve.local").orElseThrow()));

            bootstrap.run(null);

            assertThat(searchRepository.search(SearchFilter.of(null), PageRequest.of(0, 10)).getTotalElements())
                    .isEqualTo(1);
        }
    }
}
