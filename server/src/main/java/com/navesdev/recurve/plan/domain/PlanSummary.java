package com.navesdev.recurve.plan.domain;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.Mapping;
import org.springframework.data.elasticsearch.annotations.Setting;

/**
 * What a listing shows of a plan (FR-02.5): the read model, indexed in
 * Elasticsearch and served from there (FR-06.2, FR-07). {@link Plan} is
 * the write model; this is a projection of it, rebuilt from it at any
 * time, and holds no rule.
 *
 * <p>{@code activeIntervals} is derived here so that "a plan with an
 * active price in this cycle" is a plain filter on one field. Amounts
 * travel as text, so they never pass through a {@code double}.
 *
 * <p>Mapping annotations only. The index is created by
 * {@code PlanIndexBootstrap}, never on demand, so that it always carries
 * the analyzers from {@code search/plans-settings.json}.
 */
@Document(indexName = "#{@environment.getProperty('recurve.search.index-prefix', '')}plans", createIndex = false)
@Setting(settingPath = "search/plans-settings.json")
@Mapping(mappingPath = "search/plans-mapping.json")
public record PlanSummary(
        @Id UUID id,
        String name,
        String description,
        boolean active,
        @Field(type = FieldType.Date, format = DateFormat.date_time) Instant createdAt,
        Set<BillingInterval> activeIntervals,
        List<Price> prices) {

    public static PlanSummary of(Plan plan) {
        List<PlanPrice> prices = plan.getPrices();
        return new PlanSummary(
                plan.getId(),
                plan.getName(),
                plan.getDescription(),
                plan.isActive(),
                plan.getCreatedAt(),
                prices.stream()
                        .filter(PlanPrice::isActive)
                        .map(PlanPrice::getInterval)
                        .collect(Collectors.toCollection(() -> EnumSet.noneOf(BillingInterval.class))),
                prices.stream().map(Price::of).toList());
    }

    /** One price as the listing shows it, inactive ones included (BR-04). */
    public record Price(
            UUID id,
            String price,
            String currency,
            BillingInterval interval,
            boolean active,
            @Field(type = FieldType.Date, format = DateFormat.date_time) Instant createdAt) {

        static Price of(PlanPrice price) {
            return new Price(
                    price.getId(),
                    price.getPrice().toPlainString(),
                    price.getCurrency(),
                    price.getInterval(),
                    price.isActive(),
                    price.getCreatedAt());
        }
    }
}
