package com.navesdev.recurve.user.repository;

import java.util.Collection;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.RefreshPolicy;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHitSupport;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Repository;

import com.navesdev.recurve.user.domain.UserSummary;
import com.navesdev.recurve.user.service.UserFilter;

import lombok.RequiredArgsConstructor;

/**
 * The operator read model's store (FR-06, FR-07). A narrow surface on
 * purpose, in the spirit of {@code BaseRepository}: it indexes, searches
 * and rebuilds — nothing deletes a single document, because nothing in
 * Recurve deletes a record; an index is only ever rebuilt whole.
 *
 * <p>A single {@link #save} is visible to the next search at once
 * (refresh on write): an operator who deactivates a colleague expects the
 * listing to say so on the next request, and the write volume here is
 * small. A rebuild indexes in bulk without refreshing and refreshes once
 * at the end.
 */
@Repository
@RequiredArgsConstructor
public class UserSearchRepository {

    private final ElasticsearchOperations operations;

    public UserSummary save(UserSummary summary) {
        return operations.withRefreshPolicy(RefreshPolicy.IMMEDIATE).save(summary);
    }

    /** Bulk, not refreshed: for a rebuild, which calls {@link #refresh()} once at the end. */
    public void saveAll(Collection<UserSummary> summaries) {
        operations.withRefreshPolicy(RefreshPolicy.NONE).save(summaries);
    }

    public Page<UserSummary> search(UserFilter filter, Pageable pageable) {
        SearchHits<UserSummary> hits = operations.search(
                UserSearchQueries.from(filter, pageable), UserSummary.class);

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
        return operations.indexOps(UserSummary.class);
    }
}
