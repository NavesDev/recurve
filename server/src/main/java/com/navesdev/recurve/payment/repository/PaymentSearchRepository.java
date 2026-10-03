package com.navesdev.recurve.payment.repository;

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

import com.navesdev.recurve.payment.domain.PaymentSummary;
import com.navesdev.recurve.shared.repository.SearchQueries;
import com.navesdev.recurve.shared.service.SearchFilter;

import lombok.RequiredArgsConstructor;

/**
 * The payment read model's store (FR-04.6, FR-07), with the same narrow
 * surface as the operator's: it indexes, searches and rebuilds; nothing
 * deletes a single document. A single {@link #save} is visible to the next
 * search at once; a rebuild indexes in bulk and refreshes once at the end.
 */
@Repository
@RequiredArgsConstructor
public class PaymentSearchRepository {

    /** What {@code q} matches: the gateway's id of a charge, exactly, for an operator who has one in hand. */
    private static final List<String> SEARCHED_FIELDS = List.of("externalId");

    private final ElasticsearchOperations operations;

    public PaymentSummary save(PaymentSummary summary) {
        return operations.withRefreshPolicy(RefreshPolicy.IMMEDIATE).save(summary);
    }

    /** Bulk, not refreshed: for a rebuild, which calls {@link #refresh()} once at the end. */
    public void saveAll(Collection<PaymentSummary> summaries) {
        operations.withRefreshPolicy(RefreshPolicy.NONE).save(summaries);
    }

    public Page<PaymentSummary> search(SearchFilter filter, Pageable pageable) {
        SearchHits<PaymentSummary> hits = operations.search(
                SearchQueries.from(filter, pageable, SEARCHED_FIELDS), PaymentSummary.class);

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
        return operations.indexOps(PaymentSummary.class);
    }
}
