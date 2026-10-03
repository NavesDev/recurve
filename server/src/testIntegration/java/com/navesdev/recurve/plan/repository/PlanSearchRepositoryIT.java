package com.navesdev.recurve.plan.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

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

import com.navesdev.recurve.plan.domain.BillingInterval;
import com.navesdev.recurve.plan.domain.Plan;
import com.navesdev.recurve.plan.domain.PlanPrice;
import com.navesdev.recurve.plan.domain.PlanSummary;
import com.navesdev.recurve.shared.service.SearchFilter;

/**
 * The plan listing rules (FR-06.2, FR-07) against a real Elasticsearch, on
 * the index the mapping in {@code search/} produces. The index is
 * recreated before each test: there is no transaction to roll back.
 */
@DataElasticsearchTest
@Import(PlanSearchRepository.class)
class PlanSearchRepositoryIT {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    private static final Sort BY_NAME = Sort.by(Sort.Order.asc("name.keyword"), Sort.Order.asc("id"));

    @Autowired
    private PlanSearchRepository repository;

    @BeforeEach
    void setUp() {
        repository.recreateIndex();

        Plan pro = Plan.create("Pro Teams", "For teams", NOW);
        pro.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW);
        pro.addPrice(new BigDecimal("499.00"), "BRL", BillingInterval.YEARLY, NOW);

        Plan basic = Plan.create("Basic", "Pro features, fewer seats", NOW);
        PlanPrice retired = basic.addPrice(new BigDecimal("19.90"), "BRL", BillingInterval.MONTHLY, NOW);
        basic.deactivatePrice(retired.getId());
        basic.addPrice(new BigDecimal("199.00"), "BRL", BillingInterval.YEARLY, NOW);

        Plan legacy = Plan.create("Legacy", null, NOW);
        legacy.addPrice(new BigDecimal("9.90"), "USD", BillingInterval.MONTHLY, NOW);
        legacy.deactivate();

        List.of(pro, basic, legacy).forEach(plan -> repository.save(PlanSummary.of(plan)));
    }

    @Nested
    @DisplayName("FR-06.2 searching by name")
    class Searching {

        @Test
        void theSearchMatchesTheStartOfAnyWordOfTheName() {
            assertThat(names(search(SearchFilter.of("tea")))).containsExactly("Pro Teams");
        }

        @Test
        void theDescriptionIsNotSearched() {
            // "Basic"'s description says "Pro"; only names are searched.
            assertThat(names(search(SearchFilter.of("pro")))).containsExactly("Pro Teams");
        }

        @Test
        void anAbsentSearchMatchesEveryPlan() {
            assertThat(search(SearchFilter.of(null))).hasSize(3);
        }
    }

    @Nested
    @DisplayName("FR-06.2 filtering by a cycle with an active price")
    class ByCycle {

        @Test
        void aPlanMatchesWhenItHasAnActivePriceInThatCycle() {
            // Basic's monthly price is inactive, so Basic is not on sale monthly.
            assertThat(names(search(filteredBy("activeIntervals", "MONTHLY")))).containsExactly("Legacy", "Pro Teams");
        }

        @Test
        void severalCyclesMatchAnyOfThem() {
            assertThat(search(filteredBy("activeIntervals", "MONTHLY", "YEARLY"))).hasSize(3);
        }

        @Test
        void theCycleAndThePlansOwnStateCombine() {
            SearchFilter both = new SearchFilter(null,
                    Map.of("activeIntervals", List.of("MONTHLY"), "active", List.of("true")));

            assertThat(names(search(both))).containsExactly("Pro Teams");
        }
    }

    @Nested
    @DisplayName("FR-06.2 sorting and FR-07 paging")
    class SortingAndPaging {

        @Test
        void byNameAscendingAndDescending() {
            assertThat(names(search(SearchFilter.of(null)))).containsExactly("Basic", "Legacy", "Pro Teams");

            Page<PlanSummary> descending = repository.search(SearchFilter.of(null),
                    PageRequest.of(0, 20, Sort.by(Sort.Order.desc("name.keyword"), Sort.Order.asc("id"))));
            assertThat(names(descending)).containsExactly("Pro Teams", "Legacy", "Basic");
        }

        @Test
        void aPageCarriesTheTotalBeforePaging() {
            Page<PlanSummary> page = repository.search(SearchFilter.of(null), PageRequest.of(1, 2, BY_NAME));

            assertThat(names(page)).containsExactly("Pro Teams");
            assertThat(page.getTotalElements()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("FR-02.5 a plan comes back with its prices")
    class Prices {

        @Test
        void thePricesComeBackAsIndexed() {
            PlanSummary basic = search(SearchFilter.of("basic")).getContent().getFirst();

            assertThat(basic.prices()).hasSize(2);
            assertThat(basic.prices()).extracting(PlanSummary.Price::price).containsExactly("19.90", "199.00");
            assertThat(basic.prices()).extracting(PlanSummary.Price::active).containsExactly(false, true);
            assertThat(basic.prices().getFirst().createdAt()).isEqualTo(NOW);
            assertThat(basic.activeIntervals()).containsExactly(BillingInterval.YEARLY);
        }
    }

    private Page<PlanSummary> search(SearchFilter filter) {
        return repository.search(filter, PageRequest.of(0, 20, BY_NAME));
    }

    private static SearchFilter filteredBy(String field, String... values) {
        return new SearchFilter(null, Map.of(field, List.of(values)));
    }

    private static List<String> names(Page<PlanSummary> page) {
        return page.getContent().stream().map(PlanSummary::name).toList();
    }
}
