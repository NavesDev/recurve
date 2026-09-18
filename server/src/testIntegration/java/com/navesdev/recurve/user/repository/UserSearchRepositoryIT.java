package com.navesdev.recurve.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.elasticsearch.test.autoconfigure.DataElasticsearchTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import com.navesdev.recurve.user.domain.Permission;
import com.navesdev.recurve.user.domain.User;
import com.navesdev.recurve.user.domain.UserSummary;
import com.navesdev.recurve.user.service.UserFilter;
import com.navesdev.recurve.user.service.UserFilterField;

/**
 * The listing rules (FR-06, FR-07) against a real Elasticsearch, on the
 * index the mapping in {@code search/} produces. The index is recreated
 * before each test: there is no transaction to roll back.
 */
@DataElasticsearchTest
@Import(UserSearchRepository.class)
class UserSearchRepositoryIT {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    @Autowired
    private UserSearchRepository repository;

    @BeforeEach
    void setUp() {
        repository.recreateIndex();
    }

    @Nested
    @DisplayName("FR-06 searching and filtering")
    class SearchingAndFiltering {

        @BeforeEach
        void index() {
            UserSearchRepositoryIT.this.index("Ada Lovelace", "ada@recurve.local");
            UserSearchRepositoryIT.this.index("Grace Hopper", "grace@recurve.local");
            UserSearchRepositoryIT.this.index("Alan Turing", "alan@example.com");
        }

        @Test
        void theSearchMatchesAWholeWordOfAName() {
            assertThat(names(search(UserFilter.of("Lovelace")))).containsExactly("Ada Lovelace");
        }

        @Test
        void theSearchMatchesTheStartOfAnyWordOfAName() {
            assertThat(names(search(UserFilter.of("Love")))).containsExactly("Ada Lovelace");
            assertThat(names(search(UserFilter.of("hopp")))).containsExactly("Grace Hopper");
        }

        @Test
        void theSearchMatchesTheStartOfAnEmail() {
            assertThat(names(search(UserFilter.of("ala")))).containsExactly("Alan Turing");
        }

        @Test
        void theSearchMatchesTheDomainOfAnEmail() {
            assertThat(names(search(UserFilter.of("example.com")))).containsExactly("Alan Turing");
        }

        @Test
        void theSearchIgnoresCase() {
            assertThat(names(search(UserFilter.of("lovelace"))))
                    .isEqualTo(names(search(UserFilter.of("LOVELACE"))));
        }

        @Test
        void theSearchDoesNotMatchFromTheMiddleOfAWord() {
            assertThat(search(UserFilter.of("urin"))).isEmpty();
            assertThat(search(UserFilter.of("velace"))).isEmpty();
        }

        @Test
        void everyWordTypedMustMatch() {
            assertThat(names(search(UserFilter.of("ada love")))).containsExactly("Ada Lovelace");
            assertThat(search(UserFilter.of("ada hopper"))).isEmpty();
        }

        @Test
        void anAbsentSearchMatchesEveryone() {
            assertThat(search(UserFilter.of(null))).hasSize(3);
            assertThat(search(UserFilter.of("  "))).hasSize(3);
        }

        @Test
        void filteringSeparatesActiveFromInactiveOperators() {
            deactivate("alan@example.com");

            assertThat(search(filteredBy("true"))).hasSize(2);
            assertThat(search(filteredBy("false"))).hasSize(1);
        }

        @Test
        void anAbsentFilterMatchesBoth() {
            deactivate("alan@example.com");

            assertThat(search(UserFilter.of(null))).hasSize(3);
        }

        @Test
        void severalValuesOfOneFilterMatchAnyOfThem() {
            deactivate("alan@example.com");

            assertThat(search(filteredBy("true", "false"))).hasSize(3);
        }

        @Test
        void aSearchAndAFilterMustBothMatch() {
            deactivate("ada@recurve.local");

            UserFilter both = new UserFilter("recurve.local",
                    Map.of(UserFilterField.ACTIVE, List.of("true")));

            assertThat(names(search(both))).containsExactly("Grace Hopper");
        }
    }

    @Nested
    @DisplayName("FR-06.1 sorting")
    class Sorting {

        @BeforeEach
        void index() {
            UserSearchRepositoryIT.this.index("bob Builder", "zed@recurve.local");
            UserSearchRepositoryIT.this.index("Ada Lovelace", "ada@recurve.local");
            UserSearchRepositoryIT.this.index("Carol Danvers", "mid@recurve.local");
        }

        @Test
        void byNameIgnoresCase() {
            assertThat(names(page(0, 10, Sort.by("name"))))
                    .containsExactly("Ada Lovelace", "bob Builder", "Carol Danvers");
        }

        @Test
        void byEmailDescending() {
            assertThat(page(0, 10, Sort.by(Sort.Direction.DESC, "email")).map(UserSummary::email))
                    .containsExactly("zed@recurve.local", "mid@recurve.local", "ada@recurve.local");
        }

        @Test
        void byCreationDate() {
            repository.recreateIndex();
            repository.save(summary("Second", "second@recurve.local", NOW.plusSeconds(60)));
            repository.save(summary("First", "first@recurve.local", NOW));

            assertThat(names(page(0, 10, Sort.by("createdAt")))).containsExactly("First", "Second");
        }
    }

    @Nested
    @DisplayName("FR-07 pagination")
    class Pagination {

        @Test
        void theTotalCountsEveryMatchNotJustThePage() {
            indexMany(5, "Operator");

            Page<UserSummary> firstPage = page(0, 2, Sort.by("name"));

            assertThat(firstPage.getContent()).hasSize(2);
            assertThat(firstPage.getTotalElements()).isEqualTo(5);
        }

        @Test
        void theTotalCountsWhatMatchedTheSearchNotTheWholeIndex() {
            indexMany(5, "Operator");
            index("Ada Lovelace", "ada@recurve.local");

            Page<UserSummary> found = repository.search(UserFilter.of("Lovelace"),
                    PageRequest.of(0, 2, Sort.by("name")));

            assertThat(found.getTotalElements()).isEqualTo(1);
        }

        @Test
        void aPageBeyondTheEndIsEmptyButStillReportsTheTotal() {
            indexMany(3, "Operator");

            Page<UserSummary> beyond = page(50, 20, Sort.by("name"));

            assertThat(beyond.getContent()).isEmpty();
            assertThat(beyond.getTotalElements()).isEqualTo(3);
        }

        @Test
        void everyOperatorAppearsExactlyOnceWhenPagingThroughTiedNames() {
            // FR-07.4: every operator here sorts identically by name, so
            // only the secondary key keeps the pages from overlapping.
            indexMany(5, "Same Name");

            List<UUID> seen = new ArrayList<>();
            Sort byTiedField = Sort.by("name").ascending().and(Sort.by("id").ascending());
            for (int number = 0; number < 3; number++) {
                page(number, 2, byTiedField).forEach(operator -> seen.add(operator.id()));
            }

            assertThat(seen).hasSize(5).doesNotHaveDuplicates();
        }
    }

    private Page<UserSummary> page(int number, int size, Sort sort) {
        return repository.search(UserFilter.of(null), PageRequest.of(number, size, sort));
    }

    private Page<UserSummary> search(UserFilter filter) {
        return repository.search(filter, PageRequest.of(0, 20, Sort.by("name")));
    }

    private static UserFilter filteredBy(String... active) {
        return new UserFilter(null, Map.of(UserFilterField.ACTIVE, List.of(active)));
    }

    private static List<String> names(Page<UserSummary> page) {
        return page.getContent().stream().map(UserSummary::name).toList();
    }

    private void indexMany(int howMany, String sharedName) {
        for (int index = 0; index < howMany; index++) {
            index(sharedName, "operator%d@recurve.local".formatted(index));
        }
    }

    private UserSummary index(String name, String email) {
        return repository.save(summary(name, email, NOW));
    }

    private static UserSummary summary(String name, String email, Instant createdAt) {
        return new UserSummary(UUID.randomUUID(), name, email, Set.of(Permission.VIEW_USERS), true, createdAt);
    }

    private void deactivate(String email) {
        UserSummary current = search(UserFilter.of(email)).getContent().getFirst();
        User user = User.create(current.name(), current.email(), "$2a$10$hash", current.permissions(), NOW);
        user.deactivate();
        // Same id, so the document is replaced rather than added.
        repository.save(new UserSummary(current.id(), user.getName(), user.getEmail(),
                user.getPermissions(), user.isActive(), current.createdAt()));
    }
}
