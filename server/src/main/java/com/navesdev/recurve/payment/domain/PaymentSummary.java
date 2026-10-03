package com.navesdev.recurve.payment.domain;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.Mapping;
import org.springframework.data.elasticsearch.annotations.Setting;

/**
 * What a listing shows of a payment (FR-04.6): the read model, indexed in
 * Elasticsearch and served from there (FR-07). {@link Payment} is the
 * write model; this is a projection of it and holds no rule.
 *
 * <p>The subscriber is named by id only: its name changes (FR-03.7), and a
 * copy here would have to follow it. The amount travels as text, so it
 * never passes through a {@code double}; the mapping reads it as a number
 * to sort on.
 *
 * <p>Mapping annotations only. The index is created by
 * {@code PaymentIndexBootstrap}, never on demand.
 */
@Document(indexName = "#{@environment.getProperty('recurve.search.index-prefix', '')}payments", createIndex = false)
@Setting(settingPath = "search/payments-settings.json")
@Mapping(mappingPath = "search/payments-mapping.json")
public record PaymentSummary(
        @Id UUID id,
        UUID subscriberId,
        String amount,
        String currency,
        PaymentStatus status,
        @Field(type = FieldType.Date, format = DateFormat.date_time) Instant dueAt,
        @Field(type = FieldType.Date, format = DateFormat.date_time) Instant paidAt,
        @Field(type = FieldType.Date, format = DateFormat.date_time) Instant refundedAt,
        String externalId,
        String invoiceUrl,
        @Field(type = FieldType.Date, format = DateFormat.date_time) Instant createdAt) {

    public static PaymentSummary of(Payment payment) {
        return new PaymentSummary(
                payment.getId(),
                payment.getSubscriberId(),
                payment.getAmount().toPlainString(),
                payment.getCurrency(),
                payment.getStatus(),
                payment.getDueAt(),
                payment.getPaidAt(),
                payment.getRefundedAt(),
                payment.getExternalId(),
                payment.getInvoiceUrl(),
                payment.getCreatedAt());
    }
}
