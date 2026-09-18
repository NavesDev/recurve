package com.navesdev.recurve.user.repository;

import java.util.Optional;
import java.util.UUID;

import com.navesdev.recurve.shared.repository.BaseRepository;
import com.navesdev.recurve.user.domain.User;

public interface UserRepository extends BaseRepository<User, UUID> {

    /** The login lookup (FR-05.1). */
    Optional<User> findByEmail(String email);

    /** BR-02, on creation. */
    boolean existsByEmail(String email);

    /** BR-02, on update: the operator keeping its own email is not a clash. */
    boolean existsByEmailAndIdNot(String email, UUID id);
}
