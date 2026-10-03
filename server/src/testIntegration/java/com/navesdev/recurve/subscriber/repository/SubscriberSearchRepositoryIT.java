package com.navesdev.recurve.subscriber.repository;

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
import com.navesdev.recurve.shared.service.SearchFilter;
import com.navesdev.recurve.subscriber.domain.Subscriber;
import com.navesdev.recurve.subscriber.domain.SubscriberStatus;
import com.navesdev.recurve.subscriber.domain.SubscriberSummary;

/**
 * The subscriber listing rules (FR-06.3, FR-07) against a real
 * Elasticsearch, on the index the mapping in {@code search/} produces. The
 * index is recreated before each test: there is no transaction to roll
 * back.
 */
@DataElasticsearchTest
@Import(SubscriberSearchRepository.class)
class SubscriberSearchRepositoryIT {

    private static final Instant JAN = Instant.parse("2026-01-15T10:00:00Z");
    private static final Instant FEB = Instant.parse("2026-02-15T10:00:00Z");
    private static final Instant MAR = Instant.parse("2026-03-15T10:00:00Z");
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("startedAt"), Sort.Order.asc("id"));

    @Autowired
    private SubscriberSearchRepository repository;

    private Plan pro;

    @BeforeEach
    void setUp() {
        repository.recreateIndex();

        pro = Plan.create("Pro", null, JAN);
        PlanPrice proMonthly = pro.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, JAN);
        PlanPrice proYearly = pro.addPrice(new BigDecimal("499.00"), "BRL", BillingInterval.YEARLY, JAN);
        Plan basic = Plan.create("Basic", null, JAN);
        PlanPrice basicMonthly = basic.addPrice(new BigDecimal("9.90"), "BRL", BillingInterval.MONTHLY, JAN);

        Subscriber grace = Subscriber.start("Grace Hopper", "grace@navy.mil", "52998224725", proMonthly, JAN);
        Subscriber ada = Subscriber.start("Ada Lovelace", "ada@engine.org", "52998224725", proYearly, FEB);
        Subscriber alan = Subscriber.start("Alan Turing", "alan@bletchley.uk", "52998224725", basicMonthly, MAR);
        alan.cancel(MAR);

        repository.save(SubscriberSummary.of(grace, pro));
        repository.save(SubscriberSummary.of(ada, pro));
        repository.save(SubscriberSummary.of(alan, basic));
    }

    @Nested
    @DisplayName("FR-06.3 searching by name and email")
    class Searching {

        @Test
        void theSearchMatchesTheStartOfAnyWordOfTheName() {
            assertThat(names(search(SearchFilter.of("hop")))).containsExactly("Grace Hopper");
        }

        @Test
        void theSearchMatchesTheEmailToo() {
            assertThat(names(search(SearchFilter.of("bletchley")))).containsExactly("Alan Turing");
        }

        @Test
        void anAbsentSearchMatchesEverySubscriber() {
            assertThat(search(SearchFilter.of(null))).hasSize(3);
        }
    }

    @Nested
    @DisplayName("FR-06.3 filtering")
    class Filtering {

        @Test
        void byStatus() {
            assertThat(names(search(filteredBy("status", "CANCELED")))).containsExactly("Alan Turing");
        }

        @Test
        void severalStatusesMatchAnyOfThem() {
            assertThat(search(filteredBy("status", "ACTIVE", "CANCELED"))).hasSize(3);
        }

        @Test
        void byPlanWhicheverOfItsPricesTheSubscriberPays() {
            assertThat(names(search(filteredBy("planId", pro.getId().toString()))))
                    .containsExactly("Ada Lovelace", "Grace Hopper");
        }

        @Test
        void statusAndPlanCombine() {
            SearchFilter both = new SearchFilter(null, Map.of(
                    "planId", List.of(pro.getId().toString()),
                    "status", List.of(SubscriberStatus.CANCELED.name())));

            assertThat(search(both)).isEmpty();
        }
    }

    @Nested
    @DisplayName("FR-06.3 sorting and FR-07 paging")
    class SortingAndPaging {

        @Test
        void byStartDateNewestFirst() {
            assertThat(names(search(SearchFilter.of(null)))).containsExactly("Alan Turing", "Ada Lovelace", "Grace Hopper");
        }

        @Test
        void byBilledAmountAsANumberNotAsText() {
            // As text "9.90" would sort after "499.00".
            Page<SubscriberSummary> cheapestFirst = repository.search(SearchFilter.of(null),
                    PageRequest.of(0, 20, Sort.by(Sort.Order.asc("price"), Sort.Order.asc("id"))));

            assertThat(names(cheapestFirst)).containsExactly("Alan Turing", "Grace Hopper", "Ada Lovelace");
        }

        @Test
        void aPageCarriesTheTotalBeforePaging() {
            Page<SubscriberSummary> page = repository.search(SearchFilter.of(null), PageRequest.of(1, 2, NEWEST_FIRST));

            assertThat(names(page)).containsExactly("Grace Hopper");
            assertThat(page.getTotalElements()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("FR-03.4 a subscriber comes back as indexed")
    class RoundTrip {

        @Test
        void theAmountAndDatesComeBackUnchanged() {
            SubscriberSummary alan = search(SearchFilter.of("alan")).getContent().getFirst();

            assertThat(alan.price()).isEqualTo("9.90");
            assertThat(alan.interval()).isEqualTo(BillingInterval.MONTHLY);
            assertThat(alan.startedAt()).isEqualTo(MAR);
            assertThat(alan.canceledAt()).isEqualTo(MAR);
        }
    }

    private Page<SubscriberSummary> search(SearchFilter filter) {
        return repository.search(filter, PageRequest.of(0, 20, NEWEST_FIRST));
    }

    private static SearchFilter filteredBy(String field, String... values) {
        return new SearchFilter(null, Map.of(field, List.of(values)));
    }

    private static List<String> names(Page<SubscriberSummary> page) {
        return page.getContent().stream().map(SubscriberSummary::name).toList();
    }
}
