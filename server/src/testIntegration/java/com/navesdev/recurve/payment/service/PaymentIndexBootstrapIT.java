package com.navesdev.recurve.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.transaction.annotation.Transactional;

import com.navesdev.recurve.payment.domain.Payment;
import com.navesdev.recurve.payment.domain.PaymentSummary;
import com.navesdev.recurve.payment.repository.PaymentRepository;
import com.navesdev.recurve.payment.repository.PaymentSearchRepository;
import com.navesdev.recurve.plan.domain.BillingInterval;
import com.navesdev.recurve.plan.domain.Plan;
import com.navesdev.recurve.plan.domain.PlanPrice;
import com.navesdev.recurve.shared.service.SearchFilter;
import com.navesdev.recurve.subscriber.domain.Subscriber;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/** What startup does when the payment index is missing, and what it leaves alone when it is not. */
@SpringBootTest
@Transactional
class PaymentIndexBootstrapIT {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    @Autowired
    private PaymentIndexBootstrap bootstrap;

    @Autowired
    private PaymentRepository repository;

    @Autowired
    private PaymentSearchRepository searchRepository;

    @Autowired
    private ElasticsearchOperations operations;

    @PersistenceContext
    private EntityManager entityManager;

    private Payment payment;

    @BeforeEach
    void setUp() {
        entityManager.createNativeQuery("TRUNCATE payments, subscribers, plans CASCADE").executeUpdate();
        Plan plan = Plan.create("Pro", null, NOW);
        PlanPrice price = plan.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW);
        entityManager.persist(plan);
        Subscriber subscriber = Subscriber.start("Grace", "grace@navy.mil", "52998224725", price, NOW);
        entityManager.persist(subscriber);
        payment = repository.save(Payment.charge(subscriber, price, NOW));
        entityManager.flush();
    }

    @Test
    void aMissingIndexIsCreatedAndFilledFromTheDatabase() {
        operations.indexOps(PaymentSummary.class).delete();

        bootstrap.run(null);

        assertThat(searchRepository.indexExists()).isTrue();
        assertThat(total()).isEqualTo(1);
    }

    @Test
    void anExistingIndexIsLeftAlone() {
        searchRepository.recreateIndex();

        bootstrap.run(null);

        assertThat(total()).isZero();
    }

    private long total() {
        return searchRepository.search(SearchFilter.of(null), PageRequest.of(0, 10)).getTotalElements();
    }
}
