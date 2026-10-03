package com.navesdev.recurve.payment.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.data.jpa.repository.Query;

import com.navesdev.recurve.payment.domain.Payment;
import com.navesdev.recurve.shared.repository.BaseRepository;

public interface PaymentRepository extends BaseRepository<Payment, UUID> {

    /** One charge per cycle: whether this subscriber's cycle was already charged. */
    boolean existsBySubscriberIdAndDueAt(UUID subscriberId, Instant dueAt);

    /** The payment behind a charge at the gateway (FR-04.7). */
    Optional<Payment> findByExternalId(String externalId);

    /**
     * Every payment, for rebuilding the search index (FR-04.6) — not a
     * listing, which is always paginated (FR-07). A stream; the caller
     * closes it.
     */
    @Query("select p from Payment p")
    Stream<Payment> streamAll();
}
