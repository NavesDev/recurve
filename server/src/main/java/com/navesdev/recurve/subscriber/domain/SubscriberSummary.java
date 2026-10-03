package com.navesdev.recurve.subscriber.domain;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.Mapping;
import org.springframework.data.elasticsearch.annotations.Setting;

import com.navesdev.recurve.plan.domain.BillingInterval;
import com.navesdev.recurve.plan.domain.Plan;
import com.navesdev.recurve.plan.domain.PlanPrice;

/**
 * What a listing shows of a subscriber (FR-03.4): the read model, indexed
 * in Elasticsearch and served from there (FR-06.3, FR-07). {@link
 * Subscriber} is the write model; this is a projection of it and of the
 * price it pays, rebuilt from both at any time, and holds no rule.
 *
 * <p>The price's plan, amount, currency and cycle are copied in so that
 * "subscribers of a plan" is a filter and "by amount" a sort on one field.
 * The copy never goes stale: a price never changes any of them (BR-04),
 * and a subscriber never changes price. The plan's name is not copied —
 * renaming a plan (FR-02.6) would then have to reach every subscriber.
 * The amount travels as text, so it never passes through a {@code double};
 * the mapping reads it as a number to sort on.
 *
 * <p>Mapping annotations only. The index is created by
 * {@code SubscriberIndexBootstrap}, never on demand, so that it always
 * carries the analyzers from {@code search/subscribers-settings.json}.
 */
@Document(indexName = "#{@environment.getProperty('recurve.search.index-prefix', '')}subscribers", createIndex = false)
@Setting(settingPath = "search/subscribers-settings.json")
@Mapping(mappingPath = "search/subscribers-mapping.json")
public record SubscriberSummary(
        @Id UUID id,
        String name,
        String email,
        // Not "document": Spring Data Elasticsearch takes a property of that name for the id.
        String taxDocument,
        SubscriberStatus status,
        UUID planId,
        UUID planPriceId,
        String price,
        String currency,
        BillingInterval interval,
        @Field(type = FieldType.Date, format = DateFormat.date_time) Instant startedAt,
        @Field(type = FieldType.Date, format = DateFormat.date_time) Instant nextBillingAt,
        @Field(type = FieldType.Date, format = DateFormat.date_time) Instant canceledAt,
        @Field(type = FieldType.Date, format = DateFormat.date_time) Instant createdAt) {

    /** @param plan the plan holding the subscriber's price, whatever state either is in now */
    public static SubscriberSummary of(Subscriber subscriber, Plan plan) {
        PlanPrice price = plan.price(subscriber.getPlanPriceId());
        return new SubscriberSummary(
                subscriber.getId(),
                subscriber.getName(),
                subscriber.getEmail(),
                subscriber.getDocument(),
                subscriber.getStatus(),
                plan.getId(),
                price.getId(),
                price.getPrice().toPlainString(),
                price.getCurrency(),
                price.getInterval(),
                subscriber.getStartedAt(),
                subscriber.getNextBillingAt(),
                subscriber.getCanceledAt(),
                subscriber.getCreatedAt());
    }
}
