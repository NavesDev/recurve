package com.navesdev.recurve.user.repository;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.data.jpa.repository.Query;

import com.navesdev.recurve.shared.repository.BaseRepository;
import com.navesdev.recurve.user.domain.User;

public interface UserRepository extends BaseRepository<User, UUID> {

    /** The login lookup (FR-05.1). */
    Optional<User> findByEmail(String email);

    /** BR-02, on creation. */
    boolean existsByEmail(String email);

    /** BR-02, on update: the operator keeping its own email is not a clash. */
    boolean existsByEmailAndIdNot(String email, UUID id);

    /**
     * Every operator, for rebuilding the search index (FR-01.5) — not a
     * listing, which is always paginated (FR-07). A stream, so the rebuild
     * walks the table without holding it in memory; the caller closes it.
     */
    @Query("select u from User u")
    Stream<User> streamAll();
}
