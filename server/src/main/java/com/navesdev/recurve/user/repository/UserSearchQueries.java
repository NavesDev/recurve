package com.navesdev.recurve.user.repository;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;

import com.navesdev.recurve.user.service.UserFilter;
import com.navesdev.recurve.user.service.UserFilterField;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;

/**
 * Turns a listing filter and page into an Elasticsearch query (FR-06,
 * FR-07). Knows no business rule: which fields may be filtered or sorted
 * is decided by the allow-lists in the service, and the values arrive
 * already validated.
 *
 * <p>The search is a {@code multi_match} over the prefix-analyzed
 * {@code name} and {@code email} (see {@code search/users-settings.json}):
 * every word typed must be the start of a word in either field. Filters
 * go in the {@code filter} context, so they neither score nor need to.
 */
public final class UserSearchQueries {

    private static final List<String> SEARCHED_FIELDS = List.of("name", "email");

    private UserSearchQueries() {
    }

    public static NativeQuery from(UserFilter filter, Pageable pageable) {
        BoolQuery.Builder bool = new BoolQuery.Builder();

        if (filter.text() != null && !filter.text().isBlank()) {
            bool.must(matchesText(filter.text().trim()));
        }

        for (UserFilterField field : filter.criteria().keySet()) {
            List<String> values = filter.valuesOf(field);
            if (!values.isEmpty()) {
                bool.filter(matches(field, values));
            }
        }

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

    private static Query matchesText(String text) {
        return Query.of(query -> query.multiMatch(match -> match
                .query(text)
                .fields(SEARCHED_FIELDS)
                .type(TextQueryType.CrossFields)
                .operator(Operator.And)));
    }

    /** FR-06: several values of one field combine with OR — one terms clause. */
    private static Query matches(UserFilterField field, List<String> values) {
        List<FieldValue> terms = switch (field) {
            case ACTIVE -> values.stream()
                    .map(Boolean::parseBoolean)
                    .distinct()
                    .map(active -> FieldValue.of(active.booleanValue()))
                    .toList();
        };

        return Query.of(query -> query.terms(t -> t
                .field(field.field())
                .terms(v -> v.value(terms))));
    }

    /**
     * A text field is sorted on its {@code .keyword} copy; every other
     * field on itself. The order of keys is kept: the last one is the
     * fixed secondary key that makes paging stable (FR-07.4).
     */
    private static List<SortOptions> sortOf(Sort sort) {
        return sort.stream()
                .map(order -> SortOptions.of(options -> options.field(field -> field
                        .field(sortField(order.getProperty()))
                        .order(order.isAscending() ? SortOrder.Asc : SortOrder.Desc))))
                .toList();
    }

    private static String sortField(String property) {
        return SEARCHED_FIELDS.contains(property) ? property + ".keyword" : property;
    }
}
