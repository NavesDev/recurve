package com.navesdev.recurve.shared.repository;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;

import com.navesdev.recurve.shared.service.SearchFilter;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;

/**
 * Turns a listing filter and page into an Elasticsearch query (FR-06,
 * FR-07), the same way for every feature. It carries field names and
 * values through as they arrived: whether a field may be filtered or
 * sorted on, and whether a value fits its type, is decided by the
 * feature's index mapping and answered by Elasticsearch.
 *
 * <p>The search is a {@code multi_match} over the fields the feature
 * names, which its settings analyze by word prefix (see
 * {@code search/users-settings.json} for the pattern): every word typed
 * must be the start of a word in one of them. Filters go in the
 * {@code filter} context, so they neither score nor need to.
 */
public final class SearchQueries {

    private SearchQueries() {
    }

    /** @param searchedFields the fields the free text is matched against, in the feature's mapping */
    public static NativeQuery from(SearchFilter filter, Pageable pageable, List<String> searchedFields) {
        BoolQuery.Builder bool = new BoolQuery.Builder();

        if (filter.text() != null && !filter.text().isBlank()) {
            bool.must(matchesText(filter.text().trim(), searchedFields));
        }

        filter.criteria().forEach((field, values) -> {
            if (!values.isEmpty()) {
                bool.filter(matches(field, values));
            }
        });

        NativeQueryBuilder query = NativeQuery.builder()
                .withQuery(Query.of(q -> q.bool(bool.build())))
                // Page number and size only: the sort travels as sort options
                // below, and a sorted Pageable would apply it a second time.
                .withPageable(PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()));

        // The builder rejects an empty list; an unsorted page is simply unsorted.
        if (pageable.getSort().isSorted()) {
            query.withSort(sortOf(pageable.getSort()));
        }

        return query.build();
    }

    private static Query matchesText(String text, List<String> searchedFields) {
        return Query.of(query -> query.multiMatch(match -> match
                .query(text)
                .fields(searchedFields)
                .type(TextQueryType.CrossFields)
                .operator(Operator.And)));
    }

    /**
     * FR-06: several values of one field combine with OR — one terms clause.
     * Values travel as strings; Elasticsearch parses them against the
     * field's mapped type and rejects what does not fit.
     */
    private static Query matches(String field, List<String> values) {
        List<FieldValue> terms = values.stream()
                .distinct()
                .map(FieldValue::of)
                .toList();

        return Query.of(query -> query.terms(t -> t
                .field(field)
                .terms(v -> v.value(terms))));
    }

    /**
     * Sort keys as named by the caller, in order: the last one is the fixed
     * secondary key that makes paging stable (FR-07.4).
     */
    private static List<SortOptions> sortOf(Sort sort) {
        return sort.stream()
                .map(order -> SortOptions.of(options -> options.field(field -> field
                        .field(order.getProperty())
                        .order(order.isAscending() ? SortOrder.Asc : SortOrder.Desc))))
                .toList();
    }
}
