package com.navesdev.recurve.plan.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.transaction.annotation.Transactional;

import com.navesdev.recurve.plan.domain.BillingInterval;
import com.navesdev.recurve.plan.domain.Plan;
import com.navesdev.recurve.plan.domain.PlanSummary;
import com.navesdev.recurve.plan.repository.PlanRepository;
import com.navesdev.recurve.plan.repository.PlanSearchRepository;
import com.navesdev.recurve.shared.service.SearchFilter;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * The index's schema migration: what startup does when the plan index is
 * missing, and what it leaves alone when it is not.
 */
@SpringBootTest
@Transactional
class PlanIndexBootstrapIT {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    @Autowired
    private PlanIndexBootstrap bootstrap;

    @Autowired
    private PlanRepository repository;

    @Autowired
    private PlanSearchRepository searchRepository;

    @Autowired
    private ElasticsearchOperations operations;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        entityManager.createNativeQuery("TRUNCATE plans CASCADE").executeUpdate();
        Plan pro = Plan.create("Pro Teams", null, NOW);
        pro.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW);
        repository.save(pro);
        repository.save(Plan.create("Basic", null, NOW));
        entityManager.flush();
    }

    @Nested
    @DisplayName("A missing index is created and filled from the database")
    class MissingIndex {

        @Test
        void everyPlanInTheDatabaseIsInTheNewIndex() {
            operations.indexOps(PlanSummary.class).delete();

            bootstrap.run(null);

            assertThat(searchRepository.indexExists()).isTrue();
            assertThat(total(SearchFilter.of(null))).isEqualTo(2);
        }

        @Test
        void theNewIndexCarriesTheMappingNotAGuessedOne() {
            operations.indexOps(PlanSummary.class).delete();

            bootstrap.run(null);

            // Word-prefix matching only exists with the analyzer from search/plans-settings.json.
            assertThat(total(SearchFilter.of("tea"))).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("An existing index is left alone")
    class ExistingIndex {

        @Test
        void startupDoesNotRebuildAnIndexThatIsAlreadyThere() {
            searchRepository.recreateIndex();
            searchRepository.save(PlanSummary.of(Plan.create("Only", null, NOW)));

            bootstrap.run(null);

            assertThat(total(SearchFilter.of(null))).isEqualTo(1);
        }
    }

    private long total(SearchFilter filter) {
        return searchRepository.search(filter, PageRequest.of(0, 10)).getTotalElements();
    }
}
