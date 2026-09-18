package com.navesdev.recurve.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;

import com.navesdev.recurve.user.service.UserFilter;
import com.navesdev.recurve.user.service.UserFilterField;

import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;

/**
 * The query is inspected as data: what a filter turns into, without a
 * server. Whether Elasticsearch answers it as FR-06 expects is the
 * integration test's job.
 */
class UserSearchQueriesTest {

    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 20, Sort.by("name"));

    @Nested
    @DisplayName("FR-06.1 search by name and email")
    class Searching {

        @Test
        void anAbsentSearchAsksForNothingInParticular() {
            assertThat(bool(UserFilter.of(null)).must()).isEmpty();
            assertThat(bool(UserFilter.of("   ")).must()).isEmpty();
        }

        @Test
        void aSearchMustMatchOverNameOrEmailWithEveryWord() {
            var match = bool(UserFilter.of("  Ada  ")).must().getFirst().multiMatch();

            assertThat(match.query()).isEqualTo("Ada");
            assertThat(match.fields()).containsExactly("name", "email");
            assertThat(match.operator()).isEqualTo(Operator.And);
        }
    }

    @Nested
    @DisplayName("FR-06 filters combine with AND, values of one filter with OR")
    class Filtering {

        @Test
        void anAbsentFilterRestrictsNothing() {
            assertThat(bool(UserFilter.of(null)).filter()).isEmpty();
        }

        @Test
        void aFilterBecomesATermsClauseOverItsField() {
            var terms = bool(activeIn("true")).filter().getFirst().terms();

            assertThat(terms.field()).isEqualTo("active");
            assertThat(terms.terms().value()).singleElement()
                    .satisfies(value -> assertThat(value.booleanValue()).isTrue());
        }

        @Test
        void severalValuesOfOneFilterTravelInTheSameTermsClause() {
            var terms = bool(activeIn("true", "false")).filter().getFirst().terms();

            assertThat(terms.terms().value()).hasSize(2);
        }

        @Test
        void aSearchAndAFilterAreBothRequired() {
            BoolQuery both = bool(new UserFilter("ada",
                    Map.of(UserFilterField.ACTIVE, List.of("true"))));

            assertThat(both.must()).hasSize(1);
            assertThat(both.filter()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("FR-06.1 and FR-07 sort, then paginate")
    class SortingAndPaging {

        @Test
        void textFieldsAreSortedOnTheirKeywordCopy() {
            var sorts = UserSearchQueries.from(UserFilter.of(null),
                    PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "email"))).getSortOptions();

            assertThat(sorts.getFirst().field().field()).isEqualTo("email.keyword");
            assertThat(sorts.getFirst().field().order()).isEqualTo(SortOrder.Desc);
        }

        @Test
        void theCreationDateIsSortedAsItself() {
            var sorts = UserSearchQueries.from(UserFilter.of(null),
                    PageRequest.of(0, 20, Sort.by("createdAt"))).getSortOptions();

            assertThat(sorts.getFirst().field().field()).isEqualTo("createdAt");
        }

        @Test
        void everySortKeyRequestedIsKeptInOrder() {
            // FR-07.4: the controller appends id ascending; the query must
            // keep it after the field the caller chose.
            var sorts = UserSearchQueries.from(UserFilter.of(null),
                    PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "name").and(Sort.by("id"))))
                    .getSortOptions();

            assertThat(sorts).hasSize(2);
            assertThat(sorts.get(1).field().field()).isEqualTo("id");
            assertThat(sorts.get(1).field().order()).isEqualTo(SortOrder.Asc);
        }

        @Test
        void thePageIsCarriedWithoutItsSortSoItIsNotAppliedTwice() {
            NativeQuery query = UserSearchQueries.from(UserFilter.of(null), PageRequest.of(3, 10, Sort.by("name")));

            assertThat(query.getPageable().getPageNumber()).isEqualTo(3);
            assertThat(query.getPageable().getPageSize()).isEqualTo(10);
            assertThat(query.getPageable().getSort().isUnsorted()).isTrue();
        }
    }

    private static BoolQuery bool(UserFilter filter) {
        return UserSearchQueries.from(filter, FIRST_PAGE).getQuery().bool();
    }

    private static UserFilter activeIn(String... values) {
        return new UserFilter(null, Map.of(UserFilterField.ACTIVE, List.of(values)));
    }
}
