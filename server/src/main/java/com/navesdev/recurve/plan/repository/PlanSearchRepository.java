package com.navesdev.recurve.plan.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.RefreshPolicy;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHitSupport;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Repository;

import com.navesdev.recurve.plan.domain.PlanSummary;
import com.navesdev.recurve.shared.repository.SearchQueries;
import com.navesdev.recurve.shared.service.SearchFilter;

import lombok.RequiredArgsConstructor;

/**
 * The plan read model's store (FR-06.2, FR-07), with the same narrow
 * surface as the operator's: it indexes, searches and rebuilds; nothing
 * deletes a single document. A single {@link #save} is visible to the next
 * search at once; a rebuild indexes in bulk and refreshes once at the end.
 */
@Repository
@RequiredArgsConstructor
public class PlanSearchRepository {

    /** FR-06.2: what {@code q} matches against. */
    private static final List<String> SEARCHED_FIELDS = List.of("name");

    private final ElasticsearchOperations operations;

    public PlanSummary save(PlanSummary summary) {
        return operations.withRefreshPolicy(RefreshPolicy.IMMEDIATE).save(summary);
    }

    /** Bulk, not refreshed: for a rebuild, which calls {@link #refresh()} once at the end. */
    public void saveAll(Collection<PlanSummary> summaries) {
        operations.withRefreshPolicy(RefreshPolicy.NONE).save(summaries);
    }

    public Page<PlanSummary> search(SearchFilter filter, Pageable pageable) {
        SearchHits<PlanSummary> hits = operations.search(
                SearchQueries.from(filter, pageable, SEARCHED_FIELDS), PlanSummary.class);

        return SearchHitSupport.searchPageFor(hits, pageable).map(SearchHit::getContent);
    }

    public boolean indexExists() {
        return indexOps().exists();
    }

    /** Drops what is there and creates the index with the settings and mapping in {@code search/}. */
    public void recreateIndex() {
        IndexOperations index = indexOps();
        if (index.exists()) {
            index.delete();
        }
        index.createWithMapping();
    }

    public void refresh() {
        indexOps().refresh();
    }

    private IndexOperations indexOps() {
        return operations.indexOps(PlanSummary.class);
    }
}
