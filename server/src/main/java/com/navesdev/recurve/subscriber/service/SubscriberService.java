package com.navesdev.recurve.subscriber.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.navesdev.recurve.plan.domain.Plan;
import com.navesdev.recurve.plan.service.PlanService;
import com.navesdev.recurve.shared.service.SearchFilter;
import com.navesdev.recurve.subscriber.domain.Subscriber;
import com.navesdev.recurve.subscriber.domain.SubscriberSummary;
import com.navesdev.recurve.subscriber.domain.SubscriberValidator;
import com.navesdev.recurve.subscriber.domain.exception.SubscriberEmailAlreadyInUseException;
import com.navesdev.recurve.subscriber.domain.exception.SubscriberNotFoundException;
import com.navesdev.recurve.subscriber.repository.SubscriberRepository;
import com.navesdev.recurve.subscriber.repository.SubscriberSearchRepository;

import lombok.RequiredArgsConstructor;

/**
 * The feature's single entry point. One method per use case: it checks
 * what needs the database (BR-02), asks the plan feature for the price,
 * calls the domain and persists.
 *
 * <p>Every write goes to PostgreSQL and to the index inside the same
 * transaction, so a failure to index rolls the write back — fail-fast.
 * Each use case answers with the subscriber as the listing shows it,
 * with the price it pays: the entity holds only the price's id, and
 * nothing above this layer may ask the plan feature for the rest.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class SubscriberService {

    private static final int REINDEX_BATCH = 500;

    private final SubscriberRepository repository;
    private final SubscriberSearchRepository searchRepository;
    private final PlanService planService;
    private final Clock clock;

    public SubscriberSummary create(CreateSubscriberCommand command) {
        String email = SubscriberValidator.normalizeEmail(command.email());
        if (repository.existsByEmail(email)) {
            throw new SubscriberEmailAlreadyInUseException(email);
        }
        Plan plan = planService.findForSubscription(command.planPriceId());
        Subscriber subscriber = Subscriber.start(
                command.name(), email, command.document(), plan.price(command.planPriceId()), clock.instant());
        return persist(subscriber, plan);
    }

    public SubscriberSummary update(UpdateSubscriberCommand command) {
        Subscriber subscriber = findOrThrow(command.id());
        String email = SubscriberValidator.normalizeEmail(command.email());
        if (!subscriber.getEmail().equals(email) && repository.existsByEmailAndIdNot(email, subscriber.getId())) {
            throw new SubscriberEmailAlreadyInUseException(email);
        }
        subscriber.update(command.name(), email, command.document());
        return persist(subscriber, planOf(subscriber));
    }

    /** FR-03.3: cancel without deleting. */
    public SubscriberSummary cancel(UUID id) {
        Subscriber subscriber = findOrThrow(id);
        subscriber.cancel(clock.instant());
        return persist(subscriber, planOf(subscriber));
    }

    /** The subscriber itself, for the payment feature to charge (FR-04.1). */
    @Transactional(readOnly = true)
    public Subscriber findForBilling(UUID id) {
        return findOrThrow(id);
    }

    /** FR-04.7: the customer the subscriber became at the payment gateway. */
    public void attachGatewayCustomer(UUID id, String customerId) {
        Subscriber subscriber = findOrThrow(id);
        subscriber.attachGatewayCustomer(customerId);
        repository.save(subscriber);
    }

    /** FR-04.2: a cycle was paid; the next charge moves one cycle of the subscriber's price. */
    public SubscriberSummary confirmPayment(UUID id) {
        Subscriber subscriber = findOrThrow(id);
        Plan plan = planOf(subscriber);
        subscriber.confirmPayment(plan.price(subscriber.getPlanPriceId()).getInterval());
        return persist(subscriber, plan);
    }

    /** FR-04.3: a charge fell due unpaid. */
    public SubscriberSummary markPastDue(UUID id) {
        Subscriber subscriber = findOrThrow(id);
        subscriber.markPastDue();
        return persist(subscriber, planOf(subscriber));
    }

    @Transactional(readOnly = true)
    public SubscriberSummary findById(UUID id) {
        Subscriber subscriber = findOrThrow(id);
        return SubscriberSummary.of(subscriber, planOf(subscriber));
    }

    /** FR-06.3 and FR-07: search and filter, then sort, then paginate — all in the index. */
    @Transactional(readOnly = true)
    public Page<SubscriberSummary> search(SearchFilter filter, Pageable pageable) {
        return searchRepository.search(filter, pageable);
    }

    /**
     * Rebuilds the index from the database and returns how many
     * subscribers it holds. Each batch asks the plan feature once for the
     * plans of all its prices. {@link SubscriberIndexBootstrap} runs it at
     * startup, the reindex endpoint on request.
     */
    public long reindex() {
        searchRepository.recreateIndex();

        long indexed = 0;
        List<Subscriber> batch = new ArrayList<>(REINDEX_BATCH);
        try (Stream<Subscriber> subscribers = repository.streamAll()) {
            for (Subscriber subscriber : (Iterable<Subscriber>) subscribers::iterator) {
                batch.add(subscriber);
                if (batch.size() == REINDEX_BATCH) {
                    indexed += index(batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            indexed += index(batch);
        }

        searchRepository.refresh();
        return indexed;
    }

    private int index(List<Subscriber> batch) {
        Set<UUID> priceIds = batch.stream().map(Subscriber::getPlanPriceId).collect(Collectors.toSet());
        Map<UUID, Plan> plans = planService.findByPriceIds(priceIds);
        searchRepository.saveAll(batch.stream()
                .map(subscriber -> SubscriberSummary.of(subscriber, plans.get(subscriber.getPlanPriceId())))
                .toList());
        return batch.size();
    }

    /** Step 3 of every write: the database first, then the index that mirrors it. */
    private SubscriberSummary persist(Subscriber subscriber, Plan plan) {
        Subscriber saved = repository.save(subscriber);
        SubscriberSummary summary = SubscriberSummary.of(saved, plan);
        searchRepository.save(summary);
        return summary;
    }

    /** The plan of the price the subscriber pays, whether or not either is still on sale (FR-02.3). */
    private Plan planOf(Subscriber subscriber) {
        UUID priceId = subscriber.getPlanPriceId();
        return planService.findByPriceIds(Set.of(priceId)).get(priceId);
    }

    private Subscriber findOrThrow(UUID id) {
        return repository.findById(id).orElseThrow(() -> new SubscriberNotFoundException(id));
    }
}
