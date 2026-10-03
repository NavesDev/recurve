package com.navesdev.recurve.payment.repository;

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

import com.navesdev.recurve.payment.domain.Payment;
import com.navesdev.recurve.payment.domain.PaymentSummary;
import com.navesdev.recurve.plan.domain.BillingInterval;
import com.navesdev.recurve.plan.domain.Plan;
import com.navesdev.recurve.plan.domain.PlanPrice;
import com.navesdev.recurve.shared.service.SearchFilter;
import com.navesdev.recurve.subscriber.domain.Subscriber;

/**
 * The payment listing rules (FR-04.6, FR-07) against a real
 * Elasticsearch, on the index the mapping in {@code search/} produces.
 */
@DataElasticsearchTest
@Import(PaymentSearchRepository.class)
class PaymentSearchRepositoryIT {

    private static final Instant JAN = Instant.parse("2026-01-15T10:00:00Z");
    private static final Instant MAR = Instant.parse("2026-03-15T10:00:00Z");
    private static final Sort LATEST_DUE_FIRST = Sort.by(Sort.Order.desc("dueAt"), Sort.Order.asc("id"));

    @Autowired
    private PaymentSearchRepository repository;

    private Subscriber grace;
    private Payment graceFirst;
    private Payment adaYearly;

    @BeforeEach
    void setUp() {
        repository.recreateIndex();

        Plan plan = Plan.create("Pro", null, JAN);
        PlanPrice monthly = plan.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, JAN);
        PlanPrice yearly = plan.addPrice(new BigDecimal("499.00"), "BRL", BillingInterval.YEARLY, JAN);
        grace = Subscriber.start("Grace", "grace@navy.mil", "52998224725", monthly, JAN);
        Subscriber ada = Subscriber.start("Ada", "ada@engine.org", "11222333000181", yearly, JAN);

        graceFirst = Payment.charge(grace, monthly, JAN);
        graceFirst.registerAtGateway("pay_grace_1", null);
        graceFirst.confirm(JAN);
        grace.confirmPayment(BillingInterval.MONTHLY);
        Payment graceSecond = Payment.charge(grace, monthly, MAR);
        graceSecond.fail();
        adaYearly = Payment.charge(ada, yearly, JAN);

        List.of(graceFirst, graceSecond, adaYearly).forEach(payment -> repository.save(PaymentSummary.of(payment)));
    }

    @Nested
    @DisplayName("FR-04.6 filtering by subscriber and by status")
    class Filtering {

        @Test
        void bySubscriber() {
            assertThat(search(filteredBy("subscriberId", grace.getId().toString()))).hasSize(2);
        }

        @Test
        void byStatusWithSeveralValues() {
            assertThat(search(filteredBy("status", "PAID", "FAILED"))).hasSize(2);
            assertThat(ids(search(filteredBy("status", "PENDING")))).containsExactly(adaYearly.getId().toString());
        }

        @Test
        void byTheGatewaysIdThroughTheSearch() {
            assertThat(ids(search(SearchFilter.of("pay_grace_1")))).containsExactly(graceFirst.getId().toString());
        }
    }

    @Nested
    @DisplayName("FR-07 sorting and paging")
    class Sorting {

        @Test
        void byAmountAsANumber() {
            Page<PaymentSummary> largestFirst = repository.search(SearchFilter.of(null),
                    PageRequest.of(0, 20, Sort.by(Sort.Order.desc("amount"), Sort.Order.asc("id"))));

            assertThat(largestFirst.getContent().getFirst().amount()).isEqualTo("499.00");
        }

        @Test
        void aPageCarriesTheTotalBeforePaging() {
            Page<PaymentSummary> page = repository.search(SearchFilter.of(null), PageRequest.of(0, 1, LATEST_DUE_FIRST));

            assertThat(page.getContent()).hasSize(1);
            assertThat(page.getTotalElements()).isEqualTo(3);
        }
    }

    private Page<PaymentSummary> search(SearchFilter filter) {
        return repository.search(filter, PageRequest.of(0, 20, LATEST_DUE_FIRST));
    }

    private static SearchFilter filteredBy(String field, String... values) {
        return new SearchFilter(null, Map.of(field, List.of(values)));
    }

    private static List<String> ids(Page<PaymentSummary> page) {
        return page.getContent().stream().map(summary -> summary.id().toString()).toList();
    }
}
