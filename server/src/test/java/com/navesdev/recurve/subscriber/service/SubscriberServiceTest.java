package com.navesdev.recurve.subscriber.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.util.Map;
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
import com.navesdev.recurve.plan.domain.exception.PriceInactiveException;
import com.navesdev.recurve.plan.service.PlanService;
import com.navesdev.recurve.shared.service.SearchFilter;
import com.navesdev.recurve.subscriber.domain.Subscriber;
import com.navesdev.recurve.subscriber.domain.SubscriberStatus;
import com.navesdev.recurve.subscriber.domain.SubscriberSummary;
import com.navesdev.recurve.subscriber.domain.exception.SubscriberEmailAlreadyInUseException;
import com.navesdev.recurve.subscriber.domain.exception.SubscriberNotFoundException;
import com.navesdev.recurve.subscriber.repository.SubscriberRepository;
import com.navesdev.recurve.subscriber.repository.SubscriberSearchRepository;

/**
 * The orchestration around a subscriber: what is checked against the
 * database, what is asked of the plan feature, that the clock is the
 * service's, and that every write reaches the index.
 */
@ExtendWith(MockitoExtension.class)
class SubscriberServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    private static final Instant EARLIER = Instant.parse("2026-01-01T10:00:00Z");

    @Mock
    private SubscriberRepository repository;

    @Mock
    private SubscriberSearchRepository searchRepository;

    @Mock
    private PlanService planService;

    private SubscriberService service;

    private Plan plan;
    private PlanPrice price;

    @BeforeEach
    void setUp() {
        service = new SubscriberService(repository, searchRepository, planService, Clock.fixed(NOW, ZoneOffset.UTC));
        plan = Plan.create("Pro", null, EARLIER);
        price = plan.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, EARLIER);
    }

    @Nested
    @DisplayName("FR-03.1 / FR-03.2 registering a subscriber")
    class Registering {

        @Test
        void aSubscriberStartsOnThePriceNamedFromTheServicesClock() {
            when(planService.findForSubscription(price.getId())).thenReturn(plan);
            savesWhatItIsGiven();

            SubscriberSummary created = service.create(new CreateSubscriberCommand("Grace", "grace@navy.mil", "529.982.247-25", price.getId()));

            assertThat(created.startedAt()).isEqualTo(NOW);
            assertThat(created.planId()).isEqualTo(plan.getId());
            assertThat(created.price()).isEqualTo("49.90");
            verify(repository).save(argThat(saved -> saved.getPlanPriceId().equals(price.getId())));
            verify(searchRepository).save(created);
        }

        @Test
        void theEmailIsCheckedInItsCanonicalForm() {
            when(repository.existsByEmail("grace@navy.mil")).thenReturn(true);

            assertThatThrownBy(() -> service.create(new CreateSubscriberCommand("Grace", " Grace@Navy.Mil ", "529.982.247-25", price.getId())))
                    .isInstanceOf(SubscriberEmailAlreadyInUseException.class);

            verify(repository, never()).save(any());
            verifyNoInteractions(searchRepository, planService);
        }

        @Test
        void aPriceNotOnSaleSavesNothing() {
            when(planService.findForSubscription(price.getId())).thenThrow(new PriceInactiveException(price.getId()));

            assertThatThrownBy(() -> service.create(new CreateSubscriberCommand("Grace", "grace@navy.mil", "529.982.247-25", price.getId())))
                    .isInstanceOf(PriceInactiveException.class);

            verify(repository, never()).save(any());
            verifyNoInteractions(searchRepository);
        }
    }

    @Nested
    @DisplayName("FR-03.7 editing a subscriber")
    class Editing {

        @Test
        void anEditedSubscriberIsSavedAndIndexed() {
            Subscriber subscriber = stored();
            knowsThePlan();
            savesWhatItIsGiven();

            SubscriberSummary updated = service.update(
                    new UpdateSubscriberCommand(subscriber.getId(), "Grace B. Hopper", "gbh@navy.mil", "52998224725"));

            assertThat(updated.name()).isEqualTo("Grace B. Hopper");
            verify(repository).save(subscriber);
            verify(searchRepository).save(updated);
        }

        @Test
        void keepingOnesOwnEmailIsNotACollision() {
            Subscriber subscriber = stored();
            knowsThePlan();
            savesWhatItIsGiven();

            service.update(new UpdateSubscriberCommand(subscriber.getId(), "Grace B. Hopper", " GRACE@navy.mil", "52998224725"));

            verify(repository, never()).existsByEmailAndIdNot(any(), any());
        }

        @Test
        void anEmailSomeoneElseHoldsIsRefused() {
            Subscriber subscriber = stored();
            when(repository.existsByEmailAndIdNot("ada@engine.org", subscriber.getId())).thenReturn(true);

            assertThatThrownBy(() -> service.update(
                    new UpdateSubscriberCommand(subscriber.getId(), "Grace", "Ada@Engine.org", "52998224725")))
                    .isInstanceOf(SubscriberEmailAlreadyInUseException.class);

            verify(repository, never()).save(any());
            verifyNoInteractions(searchRepository);
        }
    }

    @Nested
    @DisplayName("FR-03.3 canceling a subscriber")
    class Canceling {

        @Test
        void theCancellationIsStampedWithTheServicesClock() {
            Subscriber subscriber = stored();
            knowsThePlan();
            savesWhatItIsGiven();

            SubscriberSummary canceled = service.cancel(subscriber.getId());

            assertThat(canceled.status()).isEqualTo(SubscriberStatus.CANCELED);
            assertThat(canceled.canceledAt()).isEqualTo(NOW);
            verify(repository).save(subscriber);
            verify(searchRepository).save(canceled);
        }

        @Test
        void aSubscriberStaysOnAPriceThatIsNoLongerOnSale() {
            // FR-02.3: the plan is looked up as it is, not as it is sold.
            plan.deactivatePrice(price.getId());
            Subscriber subscriber = stored();
            knowsThePlan();
            savesWhatItIsGiven();

            assertThat(service.cancel(subscriber.getId()).planPriceId()).isEqualTo(price.getId());
        }
    }

    @Nested
    @DisplayName("What does not exist")
    class Missing {

        @Test
        void anUnknownSubscriberIsNotFoundWhereverItIsAskedFor() {
            UUID unknown = UUID.randomUUID();
            when(repository.findById(unknown)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.findById(unknown)).isInstanceOf(SubscriberNotFoundException.class);
            assertThatThrownBy(() -> service.cancel(unknown)).isInstanceOf(SubscriberNotFoundException.class);
            assertThatThrownBy(() -> service.update(new UpdateSubscriberCommand(unknown, "Grace", "grace@navy.mil", "52998224725")))
                    .isInstanceOf(SubscriberNotFoundException.class);
        }

        @Test
        void aKnownSubscriberIsShownWithWhatItPays() {
            Subscriber subscriber = stored();
            knowsThePlan();

            SubscriberSummary found = service.findById(subscriber.getId());

            assertThat(found.id()).isEqualTo(subscriber.getId());
            assertThat(found.currency()).isEqualTo("BRL");
        }
    }

    @Nested
    @DisplayName("FR-06 the listing is served from the search index")
    class Indexing {

        @Test
        void aSearchGoesToTheIndex() {
            SearchFilter filter = SearchFilter.of("grace");
            Pageable page = PageRequest.of(0, 20);
            Page<SubscriberSummary> found = new PageImpl<>(List.of());
            when(searchRepository.search(filter, page)).thenReturn(found);

            assertThat(service.search(filter, page)).isSameAs(found);
            verifyNoInteractions(repository);
        }

        @Test
        void aFailureToIndexFailsTheWrite() {
            // Fail-fast: the transaction rolls back rather than leave the
            // listing out of step with the database.
            when(planService.findForSubscription(price.getId())).thenReturn(plan);
            savesWhatItIsGiven();
            doThrow(new DataAccessResourceFailureException("search is down")).when(searchRepository).save(any());

            assertThatThrownBy(() -> service.create(new CreateSubscriberCommand("Grace", "grace@navy.mil", "529.982.247-25", price.getId())))
                    .isInstanceOf(DataAccessResourceFailureException.class);
        }
    }

    @Nested
    @DisplayName("FR-03.4 the index is rebuilt from the database")
    class Reindexing {

        @Test
        void theIndexIsRecreatedAndEverySubscriberIndexedWithItsPlan() {
            Subscriber grace = Subscriber.start("Grace", "grace@navy.mil", "52998224725", price, EARLIER);
            Subscriber ada = Subscriber.start("Ada", "ada@engine.org", "52998224725", price, EARLIER);
            when(repository.streamAll()).thenReturn(Stream.of(grace, ada));
            when(planService.findByPriceIds(Set.of(price.getId()))).thenReturn(Map.of(price.getId(), plan));

            assertThat(service.reindex()).isEqualTo(2);
            verify(searchRepository).recreateIndex();
            verify(searchRepository).saveAll(argThat(batch -> batch.size() == 2
                    && batch.stream().allMatch(summary -> summary.planId().equals(plan.getId()))));
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

    private Subscriber stored() {
        Subscriber subscriber = Subscriber.start("Grace", "grace@navy.mil", "52998224725", price, EARLIER);
        when(repository.findById(subscriber.getId())).thenReturn(Optional.of(subscriber));
        return subscriber;
    }

    private void knowsThePlan() {
        when(planService.findByPriceIds(Set.of(price.getId()))).thenReturn(Map.of(price.getId(), plan));
    }

    private void savesWhatItIsGiven() {
        when(repository.save(any(Subscriber.class))).thenAnswer(call -> call.getArgument(0));
    }
}
