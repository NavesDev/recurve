package com.navesdev.recurve.subscriber.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.navesdev.recurve.plan.domain.BillingInterval;
import com.navesdev.recurve.plan.domain.Plan;
import com.navesdev.recurve.plan.domain.PlanPrice;
import com.navesdev.recurve.plan.repository.PlanRepository;
import com.navesdev.recurve.subscriber.domain.SubscriberStatus;
import com.navesdev.recurve.subscriber.domain.SubscriberSummary;
import com.navesdev.recurve.subscriber.repository.SubscriberRepository;
import com.navesdev.recurve.subscriber.repository.SubscriberSearchRepository;

/**
 * What only a real commit shows: that the container rolls a write back
 * when the index refuses it, and that a subscriber's plan is found across
 * the feature boundary once committed. Deliberately not
 * {@code @Transactional}: a test transaction would never commit. The
 * tables are truncated before each test instead; the index is mocked, so
 * nothing is left in it.
 */
@SpringBootTest
class SubscriberServiceIT {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    @Autowired
    private SubscriberService service;

    @Autowired
    private SubscriberRepository repository;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private SubscriberSearchRepository searchRepository;

    private PlanPrice price;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE subscribers, plans CASCADE");
        Plan plan = Plan.create("Pro", null, NOW);
        price = plan.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW);
        planRepository.save(plan);
    }

    @Nested
    @DisplayName("Fail-fast: the database and the index never diverge")
    class NeverDiverge {

        @Test
        void aWriteTheIndexRefusesLeavesNothingInTheDatabase() {
            doThrow(new DataAccessResourceFailureException("search node down"))
                    .when(searchRepository).save(any(SubscriberSummary.class));

            assertThatThrownBy(() -> service.create(new CreateSubscriberCommand("Grace", "grace@navy.mil", price.getId())))
                    .isInstanceOf(DataAccessResourceFailureException.class);

            assertThat(repository.count()).isZero();
        }
    }

    @Nested
    @DisplayName("FR-03 a subscriber's life, committed")
    class Lifecycle {

        @Test
        void aRegisteredSubscriberIsCanceledWithItsPriceIntact() {
            SubscriberSummary created = service.create(
                    new CreateSubscriberCommand("Grace", "grace@navy.mil", price.getId()));

            SubscriberSummary canceled = service.cancel(created.id());

            assertThat(canceled.status()).isEqualTo(SubscriberStatus.CANCELED);
            assertThat(service.findById(created.id()).price()).isEqualTo("49.90");
        }
    }
}
