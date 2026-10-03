package com.navesdev.recurve.plan.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.navesdev.recurve.plan.domain.Plan;
import com.navesdev.recurve.plan.domain.PlanSummary;
import com.navesdev.recurve.plan.domain.exception.PlanNotFoundException;
import com.navesdev.recurve.plan.domain.exception.PriceNotFoundException;
import com.navesdev.recurve.plan.repository.PlanRepository;
import com.navesdev.recurve.plan.repository.PlanSearchRepository;
import com.navesdev.recurve.shared.service.SearchFilter;

import lombok.RequiredArgsConstructor;

/**
 * The feature's single entry point. One method per use case: it loads the
 * plan, calls the domain and persists. Every rule is the aggregate's; a
 * price is reached through the plan that holds it.
 *
 * <p>Every write goes to PostgreSQL and to the index inside the same
 * transaction, so a failure to index rolls the write back — fail-fast.
 * Each use case answers with the whole plan: after a replace, the caller
 * sees the old price inactive and its successor in force.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class PlanService {

    private static final int REINDEX_BATCH = 500;

    private final PlanRepository repository;
    private final PlanSearchRepository searchRepository;
    private final Clock clock;

    public Plan create(CreatePlanCommand command) {
        return persist(Plan.create(command.name(), command.description(), clock.instant()));
    }

    public Plan update(UpdatePlanCommand command) {
        Plan plan = findOrThrow(command.id());
        plan.update(command.name(), command.description());
        return persist(plan);
    }

    /** FR-02.4: deactivate without deleting. */
    public Plan deactivate(UUID planId) {
        Plan plan = findOrThrow(planId);
        plan.deactivate();
        return persist(plan);
    }

    @Transactional(readOnly = true)
    public Plan findById(UUID planId) {
        return findOrThrow(planId);
    }

    public Plan addPrice(AddPriceCommand command) {
        Plan plan = findOrThrow(command.planId());
        plan.addPrice(command.price(), command.currency(), command.interval(), clock.instant());
        return persist(plan);
    }

    public Plan replacePrice(ReplacePriceCommand command) {
        Plan plan = findByPriceOrThrow(command.priceId());
        plan.replacePrice(command.priceId(), command.price(), clock.instant());
        return persist(plan);
    }

    public Plan deactivatePrice(UUID priceId) {
        Plan plan = findByPriceOrThrow(priceId);
        plan.deactivatePrice(priceId);
        return persist(plan);
    }

    /** FR-06.2 and FR-07: search and filter, then sort, then paginate — all in the index. */
    @Transactional(readOnly = true)
    public Page<PlanSummary> search(SearchFilter filter, Pageable pageable) {
        return searchRepository.search(filter, pageable);
    }

    /**
     * Rebuilds the index from the database and returns how many plans it
     * holds. {@link PlanIndexBootstrap} runs it at startup, the reindex
     * endpoint on request.
     */
    public long reindex() {
        searchRepository.recreateIndex();

        long indexed = 0;
        List<PlanSummary> batch = new ArrayList<>(REINDEX_BATCH);
        try (Stream<Plan> plans = repository.streamAll()) {
            for (Plan plan : (Iterable<Plan>) plans::iterator) {
                batch.add(PlanSummary.of(plan));
                if (batch.size() == REINDEX_BATCH) {
                    searchRepository.saveAll(batch);
                    indexed += batch.size();
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            searchRepository.saveAll(batch);
            indexed += batch.size();
        }

        searchRepository.refresh();
        return indexed;
    }

    /** Step 3 of every write: the database first, then the index that mirrors it. */
    private Plan persist(Plan plan) {
        Plan saved = repository.save(plan);
        searchRepository.save(PlanSummary.of(saved));
        return saved;
    }

    private Plan findOrThrow(UUID planId) {
        return repository.findById(planId).orElseThrow(() -> new PlanNotFoundException(planId));
    }

    private Plan findByPriceOrThrow(UUID priceId) {
        return repository.findByPriceId(priceId).orElseThrow(() -> new PriceNotFoundException(priceId));
    }
}
