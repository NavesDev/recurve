package com.navesdev.recurve.shared.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

/**
 * The rules every listing shares (FR-06, FR-07), stated once: what the
 * query parameters must look like and how far a page may reach. Field
 * names are not judged here — that is the index mapping's job.
 */
class ListingRequestsTest {

    private static final String BY_NAME = "name.keyword";

    private final ListingRequests listings = new ListingRequests(100);

    @Nested
    @DisplayName("FR-07 a page has a position and a bounded size")
    class Paging {

        @Test
        void thePageAndSizeAskedForAreKept() {
            var pageable = listings.parse(null, null, 3, 50, null, BY_NAME).pageable();

            assertThat(pageable.getPageNumber()).isEqualTo(3);
            assertThat(pageable.getPageSize()).isEqualTo(50);
        }

        @Test
        void aPageBeforeTheFirstIsAValidationError() {
            assertThatThrownBy(() -> listings.parse(null, null, -1, 20, null, BY_NAME))
                    .isInstanceOf(InvalidRequestException.class);
        }

        @Test
        void anEmptyPageIsAValidationError() {
            assertThatThrownBy(() -> listings.parse(null, null, 0, 0, null, BY_NAME))
                    .isInstanceOf(InvalidRequestException.class);
        }

        @Test
        void aPageLargerThanTheConfiguredMaximumIsAValidationError() {
            ListingRequests small = new ListingRequests(25);

            assertThat(small.parse(null, null, 0, 25, null, BY_NAME).pageable().getPageSize()).isEqualTo(25);
            assertThatThrownBy(() -> small.parse(null, null, 0, 26, null, BY_NAME))
                    .isInstanceOf(InvalidRequestException.class)
                    .hasMessageContaining("25");
        }

        @Test
        void aMaximumBelowOneIsAConfigurationErrorAtStartup() {
            assertThatThrownBy(() -> new ListingRequests(0)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("FR-06.1 and FR-07.4 sorting")
    class Sorting {

        @Test
        void anAbsentSortIsTheFeatureDefaultAscending() {
            Sort sort = listings.parse(null, null, 0, 20, null, BY_NAME).pageable().getSort();

            assertThat(sort.getOrderFor(BY_NAME)).isEqualTo(Sort.Order.asc(BY_NAME));
        }

        @Test
        void theFieldIsPassedThroughAsNamedWithItsDirection() {
            // No translation and no allow-list: the contract names the
            // index's own fields, and the mapping says which ones sort.
            Sort sort = listings.parse(null, null, 0, 20, "-email.keyword", BY_NAME).pageable().getSort();

            assertThat(sort.getOrderFor("email.keyword")).isEqualTo(Sort.Order.desc("email.keyword"));
        }

        @Test
        void idIsAlwaysTheLastKeyAscendingSoPagesNeverOverlap() {
            Sort descending = listings.parse(null, null, 0, 20, "-" + BY_NAME, BY_NAME).pageable().getSort();

            assertThat(descending).containsExactly(Sort.Order.desc(BY_NAME), Sort.Order.asc("id"));
        }

        @Test
        void aMinusWithNoFieldIsAValidationError() {
            assertThatThrownBy(() -> listings.parse(null, null, 0, 20, "-", BY_NAME))
                    .isInstanceOf(InvalidRequestException.class);
        }
    }

    @Nested
    @DisplayName("FR-06 free text and filters")
    class Filtering {

        @Test
        void theTextAndEachCriterionReachTheFilter() {
            var filter = listings.parse("ada", new String[] { "active:true,false", "role:admin" }, 0, 20, null, BY_NAME)
                    .filter();

            assertThat(filter.text()).isEqualTo("ada");
            assertThat(filter.valuesOf("active")).containsExactly("true", "false");
            assertThat(filter.valuesOf("role")).containsExactly("admin");
        }

        @Test
        void anAbsentFilterParameterMeansNoCriteria() {
            assertThat(listings.parse(null, null, 0, 20, null, BY_NAME).filter().criteria()).isEmpty();
        }

        @Test
        void aCriterionNotWrittenAsFieldValueIsAValidationError() {
            assertThatThrownBy(() -> listings.parse(null, new String[] { "active" }, 0, 20, null, BY_NAME))
                    .isInstanceOf(InvalidRequestException.class);
        }
    }
}
