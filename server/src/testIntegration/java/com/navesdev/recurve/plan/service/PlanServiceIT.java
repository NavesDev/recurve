package com.navesdev.recurve.plan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.navesdev.recurve.plan.domain.BillingInterval;
import com.navesdev.recurve.plan.domain.Plan;
import com.navesdev.recurve.plan.domain.PlanPrice;
import com.navesdev.recurve.plan.domain.PlanSummary;
import com.navesdev.recurve.plan.repository.PlanRepository;
import com.navesdev.recurve.plan.repository.PlanSearchRepository;

/**
 * What only a real commit shows: that the container rolls a write back
 * when the index refuses it, that a replace survives the deferred BR-03
 * check, and that a stale copy of a plan cannot overwrite a newer one.
 * Deliberately not {@code @Transactional}: a test transaction would never
 * commit. The tables are truncated before each test instead; the index is
 * mocked, so nothing is left in it.
 */
@SpringBootTest
class PlanServiceIT {

    @Autowired
    private PlanService service;

    @Autowired
    private PlanRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transaction;

    @MockitoBean
    private PlanSearchRepository searchRepository;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE plans CASCADE");
    }

    @Nested
    @DisplayName("Fail-fast: the database and the index never diverge")
    class NeverDiverge {

        @Test
        void aWriteTheIndexRefusesLeavesNothingInTheDatabase() {
            doThrow(new DataAccessResourceFailureException("search node down"))
                    .when(searchRepository).save(any(PlanSummary.class));

            assertThatThrownBy(() -> service.create(new CreatePlanCommand("Pro", null)))
                    .isInstanceOf(DataAccessResourceFailureException.class);

            assertThat(repository.count()).isZero();
        }
    }

    @Nested
    @DisplayName("BR-04 a replace commits")
    class Replacing {

        @Test
        void theOldPriceIsInactiveAndItsSuccessorInForceAfterCommit() {
            UUID planId = service.create(new CreatePlanCommand("Pro", null)).getId();
            Plan priced = service.addPrice(new AddPriceCommand(planId, new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY));
            UUID oldId = priced.getPrices().getFirst().getId();

            service.replacePrice(new ReplacePriceCommand(oldId, new BigDecimal("59.90")));

            Plan stored = service.findById(planId);
            assertThat(stored.getPrices()).hasSize(2);
            assertThat(stored.price(oldId).isActive()).isFalse();
            assertThat(stored.getPrices()).filteredOn(PlanPrice::isActive).singleElement()
                    .satisfies(successor -> assertThat(successor.getPrice()).isEqualByComparingTo("59.90"));
        }
    }

    @Nested
    @DisplayName("Concurrent writes on one plan")
    class Concurrency {

        @Test
        void aWriteOverAStaleCopyOfThePlanIsRefused() {
            // Adding a price changes the plan's own collection, which
            // increments its version: a copy read before that is stale.
            UUID planId = service.create(new CreatePlanCommand("Pro", null)).getId();
            Plan stale = service.findById(planId);
            service.addPrice(new AddPriceCommand(planId, new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY));

            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                stale.update("Pro Plus", null);
                repository.save(stale);
            })).isInstanceOf(ObjectOptimisticLockingFailureException.class);
        }
    }
}
