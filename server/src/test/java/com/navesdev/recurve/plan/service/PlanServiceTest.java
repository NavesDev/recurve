package com.navesdev.recurve.plan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.navesdev.recurve.plan.domain.BillingInterval;
import com.navesdev.recurve.plan.domain.Plan;
import com.navesdev.recurve.plan.domain.PlanPrice;
import com.navesdev.recurve.plan.domain.PlanSummary;
import com.navesdev.recurve.plan.domain.exception.PlanInactiveException;
import com.navesdev.recurve.plan.domain.exception.PlanNotFoundException;
import com.navesdev.recurve.plan.domain.exception.PriceAlreadyActiveException;
import com.navesdev.recurve.plan.domain.exception.PriceNotFoundException;
import com.navesdev.recurve.plan.repository.PlanRepository;
import com.navesdev.recurve.plan.repository.PlanSearchRepository;
import com.navesdev.recurve.shared.service.SearchFilter;

/**
 * The orchestration around the plan aggregate: what is loaded, that the
 * clock is the service's, and that every write reaches the index.
 */
@ExtendWith(MockitoExtension.class)
class PlanServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    private static final Instant EARLIER = Instant.parse("2026-01-01T10:00:00Z");
    private static final BigDecimal AMOUNT = new BigDecimal("49.90");

    @Mock
    private PlanRepository repository;

    @Mock
    private PlanSearchRepository searchRepository;

    private PlanService service;

    @BeforeEach
    void setUp() {
        service = new PlanService(repository, searchRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Nested
    @DisplayName("FR-02.1 / FR-02.6 a plan is registered and edited")
    class Plans {

        @Test
        void aRegisteredPlanIsStampedWithTheServicesClock() {
            savesWhatItIsGiven();

            Plan created = service.create(new CreatePlanCommand("Pro", "For teams"));

            assertThat(created.getCreatedAt()).isEqualTo(NOW);
            verify(repository).save(created);
        }

        @Test
        void anEditedPlanIsSaved() {
            Plan plan = Plan.create("Pro", null, EARLIER);
            when(repository.findById(plan.getId())).thenReturn(Optional.of(plan));
            savesWhatItIsGiven();

            Plan updated = service.update(new UpdatePlanCommand(plan.getId(), "Pro Plus", "More seats"));

            assertThat(updated.getName()).isEqualTo("Pro Plus");
            verify(repository).save(plan);
        }

        @Test
        void aDeactivatedPlanRemainsOnRecord() {
            Plan plan = Plan.create("Pro", null, EARLIER);
            when(repository.findById(plan.getId())).thenReturn(Optional.of(plan));
            savesWhatItIsGiven();

            assertThat(service.deactivate(plan.getId()).isActive()).isFalse();
            verify(repository).save(plan);
        }
    }

    @Nested
    @DisplayName("FR-02.2 / FR-02.3 / FR-02.7 prices change through their plan")
    class Prices {

        @Test
        void aPriceIsAddedToThePlanNamedInTheCommand() {
            Plan plan = Plan.create("Pro", null, EARLIER);
            when(repository.findById(plan.getId())).thenReturn(Optional.of(plan));
            savesWhatItIsGiven();

            Plan priced = service.addPrice(new AddPriceCommand(plan.getId(), AMOUNT, "BRL", BillingInterval.MONTHLY));

            assertThat(priced.getPrices()).singleElement()
                    .satisfies(price -> assertThat(price.getCreatedAt()).isEqualTo(NOW));
        }

        @Test
        void aRefusedPriceSavesNothing() {
            Plan plan = Plan.create("Pro", null, EARLIER);
            plan.addPrice(AMOUNT, "BRL", BillingInterval.MONTHLY, EARLIER);
            when(repository.findById(plan.getId())).thenReturn(Optional.of(plan));

            assertThatThrownBy(() -> service.addPrice(
                    new AddPriceCommand(plan.getId(), new BigDecimal("59.90"), "BRL", BillingInterval.MONTHLY)))
                    .isInstanceOf(PriceAlreadyActiveException.class);

            verify(repository, never()).save(any());
            verifyNoInteractions(searchRepository);
        }

        @Test
        void aPriceIsReplacedThroughThePlanThatHoldsIt() {
            Plan plan = Plan.create("Pro", null, EARLIER);
            PlanPrice old = plan.addPrice(AMOUNT, "BRL", BillingInterval.MONTHLY, EARLIER);
            when(repository.findByPriceId(old.getId())).thenReturn(Optional.of(plan));
            savesWhatItIsGiven();

            Plan replaced = service.replacePrice(new ReplacePriceCommand(old.getId(), new BigDecimal("59.90")));

            assertThat(replaced.getPrices()).hasSize(2);
            assertThat(replaced.getPrices().getLast().getCreatedAt()).isEqualTo(NOW);
            assertThat(old.isActive()).isFalse();
        }

        @Test
        void aPriceIsDeactivatedThroughThePlanThatHoldsIt() {
            Plan plan = Plan.create("Pro", null, EARLIER);
            PlanPrice price = plan.addPrice(AMOUNT, "BRL", BillingInterval.MONTHLY, EARLIER);
            when(repository.findByPriceId(price.getId())).thenReturn(Optional.of(plan));
            savesWhatItIsGiven();

            service.deactivatePrice(price.getId());

            assertThat(price.isActive()).isFalse();
            verify(repository).save(plan);
        }
    }

    @Nested
    @DisplayName("What does not exist")
    class Missing {

        @Test
        void aMissingPlanCannotBeReadEditedDeactivatedOrPriced() {
            UUID id = UUID.randomUUID();
            when(repository.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.findById(id)).isInstanceOf(PlanNotFoundException.class);
            assertThatThrownBy(() -> service.update(new UpdatePlanCommand(id, "Pro", null)))
                    .isInstanceOf(PlanNotFoundException.class);
            assertThatThrownBy(() -> service.deactivate(id)).isInstanceOf(PlanNotFoundException.class);
            assertThatThrownBy(() -> service.addPrice(new AddPriceCommand(id, AMOUNT, "BRL", BillingInterval.MONTHLY)))
                    .isInstanceOf(PlanNotFoundException.class);
        }

        @Test
        void aPriceNoPlanHoldsCannotBeReplacedOrDeactivated() {
            UUID id = UUID.randomUUID();
            when(repository.findByPriceId(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.replacePrice(new ReplacePriceCommand(id, AMOUNT)))
                    .isInstanceOf(PriceNotFoundException.class);
            assertThatThrownBy(() -> service.deactivatePrice(id)).isInstanceOf(PriceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("FR-06 the listing is served from the search index")
    class Indexing {

        @Test
        void everyWriteIsIndexedAsItWasSaved() {
            savesWhatItIsGiven();

            Plan created = service.create(new CreatePlanCommand("Pro", null));

            verify(searchRepository).save(argThat(summary -> summary.id().equals(created.getId())));
        }

        @Test
        void aPriceChangeReindexesItsPlan() {
            Plan plan = Plan.create("Pro", null, EARLIER);
            when(repository.findById(plan.getId())).thenReturn(Optional.of(plan));
            savesWhatItIsGiven();

            service.addPrice(new AddPriceCommand(plan.getId(), AMOUNT, "BRL", BillingInterval.YEARLY));

            verify(searchRepository).save(argThat(summary ->
                    summary.activeIntervals().contains(BillingInterval.YEARLY)));
        }

        @Test
        void anIndexingFailureFailsTheWrite() {
            // Fail-fast: the container rolls the database write back (PlanServiceIT).
            savesWhatItIsGiven();
            doThrow(new DataAccessResourceFailureException("search node down"))
                    .when(searchRepository).save(any(PlanSummary.class));

            assertThatThrownBy(() -> service.create(new CreatePlanCommand("Pro", null)))
                    .isInstanceOf(DataAccessResourceFailureException.class);
        }

        @Test
        void theListingAsksTheIndexAndNeverTheDatabase() {
            SearchFilter filter = SearchFilter.of("pro");
            Pageable page = PageRequest.of(0, 20);
            Page<PlanSummary> expected = new PageImpl<>(List.of());
            when(searchRepository.search(filter, page)).thenReturn(expected);

            assertThat(service.search(filter, page)).isSameAs(expected);
            verifyNoInteractions(repository);
        }
    }

    @Nested
    @DisplayName("FR-02.5 the index is rebuilt from the database")
    class Reindexing {

        @Test
        void theIndexIsRecreatedAndEveryPlanIndexed() {
            when(repository.streamAll()).thenReturn(Stream.of(
                    Plan.create("Pro", null, EARLIER), Plan.create("Basic", null, EARLIER)));

            assertThat(service.reindex()).isEqualTo(2);
            verify(searchRepository).recreateIndex();
            verify(searchRepository).saveAll(argThat(batch -> batch.size() == 2));
            verify(searchRepository).refresh();
        }

        @Test
        void anEmptyDatabaseStillLeavesAFreshIndexBehind() {
            when(repository.streamAll()).thenReturn(Stream.empty());

            assertThat(service.reindex()).isZero();
            verify(searchRepository).recreateIndex();
            verify(searchRepository, never()).saveAll(any());
        }
    }

    @Nested
    @DisplayName("What the subscriber feature asks of a plan")
    class ForSubscribers {

        @Test
        void aPriceOnSaleLeadsToThePlanHoldingIt() {
            Plan plan = Plan.create("Pro", null, EARLIER);
            PlanPrice price = plan.addPrice(AMOUNT, "BRL", BillingInterval.MONTHLY, EARLIER);
            when(repository.findByPriceId(price.getId())).thenReturn(Optional.of(plan));

            assertThat(service.findForSubscription(price.getId())).isSameAs(plan);
        }

        @Test
        void aPriceNoLongerOnSaleIsRefused() {
            Plan plan = Plan.create("Pro", null, EARLIER);
            PlanPrice price = plan.addPrice(AMOUNT, "BRL", BillingInterval.MONTHLY, EARLIER);
            plan.deactivate();
            when(repository.findByPriceId(price.getId())).thenReturn(Optional.of(plan));

            assertThatThrownBy(() -> service.findForSubscription(price.getId()))
                    .isInstanceOf(PlanInactiveException.class);
        }

        @Test
        void aPriceNobodyHoldsIsNotFound() {
            UUID priceId = UUID.randomUUID();
            when(repository.findByPriceId(priceId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.findForSubscription(priceId))
                    .isInstanceOf(PriceNotFoundException.class);
        }

        @Test
        void eachPriceLeadsToItsPlan() {
            Plan pro = Plan.create("Pro", null, EARLIER);
            PlanPrice proMonthly = pro.addPrice(AMOUNT, "BRL", BillingInterval.MONTHLY, EARLIER);
            PlanPrice proYearly = pro.addPrice(new BigDecimal("499.00"), "BRL", BillingInterval.YEARLY, EARLIER);
            Plan basic = Plan.create("Basic", null, EARLIER);
            PlanPrice basicMonthly = basic.addPrice(new BigDecimal("9.90"), "BRL", BillingInterval.MONTHLY, EARLIER);
            Set<UUID> priceIds = Set.of(proMonthly.getId(), proYearly.getId(), basicMonthly.getId());
            when(repository.findByPriceIds(priceIds)).thenReturn(List.of(pro, basic));

            assertThat(service.findByPriceIds(priceIds)).containsOnly(
                    entry(proMonthly.getId(), pro), entry(proYearly.getId(), pro), entry(basicMonthly.getId(), basic));
        }

        @Test
        void aPriceNobodyHoldsIsAMissingPlan() {
            // Every subscriber's price exists (a foreign key says so); one
            // that does not is a broken database, not an empty answer.
            UUID priceId = UUID.randomUUID();
            when(repository.findByPriceIds(Set.of(priceId))).thenReturn(List.of());

            assertThatThrownBy(() -> service.findByPriceIds(Set.of(priceId)))
                    .isInstanceOf(PriceNotFoundException.class);
        }

        @Test
        void noPriceAsksNothing() {
            assertThat(service.findByPriceIds(Set.of())).isEmpty();
            verifyNoInteractions(repository);
        }
    }

    private void savesWhatItIsGiven() {
        when(repository.save(any(Plan.class))).thenAnswer(call -> call.getArgument(0));
    }
}
