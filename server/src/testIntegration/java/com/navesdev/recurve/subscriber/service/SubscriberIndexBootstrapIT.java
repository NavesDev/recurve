package com.navesdev.recurve.subscriber.service;

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
import com.navesdev.recurve.plan.domain.PlanPrice;
import com.navesdev.recurve.shared.service.SearchFilter;
import com.navesdev.recurve.subscriber.domain.Subscriber;
import com.navesdev.recurve.subscriber.domain.SubscriberSummary;
import com.navesdev.recurve.subscriber.repository.SubscriberRepository;
import com.navesdev.recurve.subscriber.repository.SubscriberSearchRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * The index's schema migration: what startup does when the subscriber
 * index is missing, and what it leaves alone when it is not.
 */
@SpringBootTest
@Transactional
class SubscriberIndexBootstrapIT {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    @Autowired
    private SubscriberIndexBootstrap bootstrap;

    @Autowired
    private SubscriberRepository repository;

    @Autowired
    private SubscriberSearchRepository searchRepository;

    @Autowired
    private ElasticsearchOperations operations;

    @PersistenceContext
    private EntityManager entityManager;

    private Plan plan;
    private PlanPrice price;

    @BeforeEach
    void setUp() {
        entityManager.createNativeQuery("TRUNCATE subscribers, plans CASCADE").executeUpdate();
        plan = Plan.create("Pro", null, NOW);
        price = plan.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW);
        entityManager.persist(plan);
        repository.save(Subscriber.start("Grace Hopper", "grace@navy.mil", price, NOW));
        repository.save(Subscriber.start("Ada Lovelace", "ada@engine.org", price, NOW));
        entityManager.flush();
    }

    @Nested
    @DisplayName("A missing index is created and filled from the database")
    class MissingIndex {

        @Test
        void everySubscriberInTheDatabaseIsInTheNewIndex() {
            operations.indexOps(SubscriberSummary.class).delete();

            bootstrap.run(null);

            assertThat(searchRepository.indexExists()).isTrue();
            assertThat(total(SearchFilter.of(null))).isEqualTo(2);
        }

        @Test
        void theNewIndexCarriesTheMappingNotAGuessedOne() {
            operations.indexOps(SubscriberSummary.class).delete();

            bootstrap.run(null);

            // Word-prefix matching only exists with the analyzer from search/subscribers-settings.json.
            assertThat(total(SearchFilter.of("hop"))).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("An existing index is left alone")
    class ExistingIndex {

        @Test
        void startupDoesNotRebuildAnIndexThatIsAlreadyThere() {
            searchRepository.recreateIndex();
            searchRepository.save(SubscriberSummary.of(Subscriber.start("Only", "only@navy.mil", price, NOW), plan));

            bootstrap.run(null);

            assertThat(total(SearchFilter.of(null))).isEqualTo(1);
        }
    }

    private long total(SearchFilter filter) {
        return searchRepository.search(filter, PageRequest.of(0, 10)).getTotalElements();
    }
}
