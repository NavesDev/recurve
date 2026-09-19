package com.navesdev.recurve.shared.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;

import com.navesdev.recurve.shared.service.SearchFilter;

import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;

/**
 * The query is inspected as data: what a filter turns into, without a
 * server. Whether Elasticsearch answers it as FR-06 expects is the
 * integration test's job.
 */
class SearchQueriesTest {

    private static final List<String> SEARCHED = List.of("name", "email");
    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 20, Sort.by("name.keyword"));

    @Nested
    @DisplayName("FR-06.1 search by name and email")
    class Searching {

        @Test
        void anAbsentSearchAsksForNothingInParticular() {
            assertThat(bool(SearchFilter.of(null)).must()).isEmpty();
            assertThat(bool(SearchFilter.of("   ")).must()).isEmpty();
        }

        @Test
        void aSearchMustMatchOverTheFieldsTheFeatureNamesWithEveryWord() {
            var match = bool(SearchFilter.of("  Ada  ")).must().getFirst().multiMatch();

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
            assertThat(bool(SearchFilter.of(null)).filter()).isEmpty();
        }

        @Test
        void aFilterBecomesATermsClauseOverItsFieldWithTheValueAsWritten() {
            // The value is not parsed here: Elasticsearch reads it against
            // the field's mapped type, and rejects what does not fit.
            var terms = bool(activeIn("true")).filter().getFirst().terms();

            assertThat(terms.field()).isEqualTo("active");
            assertThat(terms.terms().value()).singleElement()
                    .satisfies(value -> assertThat(value.stringValue()).isEqualTo("true"));
        }

        @Test
        void aFieldTheListingDoesNotKnowIsPassedThroughForTheMappingToJudge() {
            var terms = bool(new SearchFilter(null, Map.of("permissions", List.of("MANAGE_SYSTEM"))))
                    .filter().getFirst().terms();

            assertThat(terms.field()).isEqualTo("permissions");
        }

        @Test
        void severalValuesOfOneFilterTravelInTheSameTermsClause() {
            var terms = bool(activeIn("true", "false")).filter().getFirst().terms();

            assertThat(terms.terms().value()).hasSize(2);
        }

        @Test
        void aSearchAndAFilterAreBothRequired() {
            BoolQuery both = bool(new SearchFilter("ada", Map.of("active", List.of("true"))));

            assertThat(both.must()).hasSize(1);
            assertThat(both.filter()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("FR-06.1 and FR-07 sort, then paginate")
    class SortingAndPaging {

        @Test
        void theSortFieldIsSentAsNamedWithItsDirection() {
            // No translation: the contract names the index's own fields, so
            // a text field is sorted on its keyword copy by asking for it.
            var sorts = SearchQueries.from(SearchFilter.of(null),
                    PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "email.keyword")), SEARCHED).getSortOptions();

            assertThat(sorts.getFirst().field().field()).isEqualTo("email.keyword");
            assertThat(sorts.getFirst().field().order()).isEqualTo(SortOrder.Desc);
        }

        @Test
        void everySortKeyRequestedIsKeptInOrder() {
            // FR-07.4: ListingRequests appends id ascending; the query must
            // keep it after the field the caller chose.
            var sorts = SearchQueries.from(SearchFilter.of(null),
                    PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "name.keyword").and(Sort.by("id"))), SEARCHED)
                    .getSortOptions();

            assertThat(sorts).hasSize(2);
            assertThat(sorts.get(1).field().field()).isEqualTo("id");
            assertThat(sorts.get(1).field().order()).isEqualTo(SortOrder.Asc);
        }

        @Test
        void anUnsortedPageAsksForNoSortAndIsStillAValidQuery() {
            NativeQuery query = SearchQueries.from(SearchFilter.of(null), PageRequest.of(0, 10), SEARCHED);

            assertThat(query.getSortOptions()).isEmpty();
        }

        @Test
        void thePageIsCarriedWithoutItsSortSoItIsNotAppliedTwice() {
            NativeQuery query = SearchQueries.from(SearchFilter.of(null), PageRequest.of(3, 10, Sort.by("name.keyword")), SEARCHED);

            assertThat(query.getPageable().getPageNumber()).isEqualTo(3);
            assertThat(query.getPageable().getPageSize()).isEqualTo(10);
            assertThat(query.getPageable().getSort().isUnsorted()).isTrue();
        }
    }

    private static BoolQuery bool(SearchFilter filter) {
        return SearchQueries.from(filter, FIRST_PAGE, SEARCHED).getQuery().bool();
    }

    private static SearchFilter activeIn(String... values) {
        return new SearchFilter(null, Map.of("active", List.of(values)));
    }
}
