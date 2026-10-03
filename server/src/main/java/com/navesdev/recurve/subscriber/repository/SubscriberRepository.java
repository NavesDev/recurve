package com.navesdev.recurve.subscriber.repository;

import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.data.jpa.repository.Query;

import com.navesdev.recurve.shared.repository.BaseRepository;
import com.navesdev.recurve.subscriber.domain.Subscriber;

public interface SubscriberRepository extends BaseRepository<Subscriber, UUID> {

    /** BR-02, on registering. The email is expected in its canonical form. */
    boolean existsByEmail(String email);

    /** BR-02, on editing: whether someone other than this subscriber holds the email. */
    boolean existsByEmailAndIdNot(String email, UUID id);

    /**
     * Every subscriber, for rebuilding the search index (FR-03.4) — not a
     * listing, which is always paginated (FR-07). A stream, so the rebuild
     * walks the table without holding it in memory; the caller closes it.
     */
    @Query("select s from Subscriber s")
    Stream<Subscriber> streamAll();
}
