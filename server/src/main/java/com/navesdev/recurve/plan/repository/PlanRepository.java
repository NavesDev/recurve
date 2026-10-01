package com.navesdev.recurve.plan.repository;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.navesdev.recurve.plan.domain.Plan;
import com.navesdev.recurve.shared.repository.BaseRepository;

public interface PlanRepository extends BaseRepository<Plan, UUID> {

    /**
     * The plan a price belongs to. A price is changed only through its
     * plan (BR-03), and the API addresses it by its own id alone.
     */
    @Query("select p from Plan p join p.prices price where price.id = :priceId")
    Optional<Plan> findByPriceId(@Param("priceId") UUID priceId);

    /**
     * Every plan, for rebuilding the search index (FR-02.5) — not a
     * listing, which is always paginated (FR-07). A stream, so the rebuild
     * walks the table without holding it in memory; the caller closes it.
     */
    @Query("select p from Plan p")
    Stream<Plan> streamAll();
}
