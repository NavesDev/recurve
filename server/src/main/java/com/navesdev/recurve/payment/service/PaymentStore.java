package com.navesdev.recurve.payment.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import com.navesdev.recurve.payment.domain.Payment;
import com.navesdev.recurve.payment.domain.PaymentSummary;
import com.navesdev.recurve.payment.domain.exception.PaymentNotFoundException;
import com.navesdev.recurve.payment.repository.PaymentRepository;
import com.navesdev.recurve.payment.repository.PaymentSearchRepository;
import com.navesdev.recurve.shared.service.SearchFilter;

import lombok.RequiredArgsConstructor;

/**
 * The payment's two stores kept in step: the database and the index that
 * mirrors it. Step 3 of every write goes through {@link #persist}, so no
 * use case can save one and forget the other. Holds no rule and opens no
 * transaction; the caller's is the one that rolls both back.
 */
@Component
@RequiredArgsConstructor
class PaymentStore {

    private static final int REINDEX_BATCH = 500;

    private final PaymentRepository repository;
    private final PaymentSearchRepository searchRepository;

    /** The database first, then the index. */
    PaymentSummary persist(Payment payment) {
        PaymentSummary summary = PaymentSummary.of(repository.save(payment));
        searchRepository.save(summary);
        return summary;
    }

    Payment findOrThrow(UUID paymentId) {
        return repository.findById(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }

    Optional<Payment> find(UUID paymentId) {
        return repository.findById(paymentId);
    }

    Optional<Payment> findByExternalId(String externalId) {
        return repository.findByExternalId(externalId);
    }

    boolean isCharged(UUID subscriberId, Instant dueAt) {
        return repository.existsBySubscriberIdAndDueAt(subscriberId, dueAt);
    }

    Page<PaymentSummary> search(SearchFilter filter, Pageable pageable) {
        return searchRepository.search(filter, pageable);
    }

    /** Recreates the index and fills it from the database; returns how many payments it holds. */
    long reindex() {
        searchRepository.recreateIndex();
        long indexed = 0;
        List<PaymentSummary> batch = new ArrayList<>(REINDEX_BATCH);
        try (Stream<Payment> payments = repository.streamAll()) {
            for (Payment payment : (Iterable<Payment>) payments::iterator) {
                batch.add(PaymentSummary.of(payment));
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
}
