package com.navesdev.recurve.shared.repository;

import java.util.Optional;

import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;

/**
 * The contract every JPA repository in the project follows.
 *
 * <p>It deliberately does not extend {@code JpaRepository}. That interface
 * carries a wide surface — {@code deleteAll}, {@code saveAll}, an
 * unbounded {@code findAll} — that the domain has no use for and that
 * invites a caller to go around a business rule. Nothing in Recurve
 * deletes a record: an operator is deactivated (FR-01.3), a plan and a
 * price are deactivated (FR-02.3, FR-02.4), a subscriber is canceled
 * (FR-03.3). A repository that cannot delete makes that structural rather
 * than a matter of discipline.
 *
 * <p>No listing either: search, filter, sort and pagination (FR-06,
 * FR-07) are served from the search index, by the feature's search
 * repository, never from a JPA query.
 *
 * <p>A feature's repository adds only what its use cases need — a lookup
 * by a natural key, an existence check — and nothing that circumvents the
 * domain.
 *
 * @param <T> the aggregate this repository stores
 * @param <I> its identifier type
 */
@NoRepositoryBean
public interface BaseRepository<T, I> extends Repository<T, I> {

    <S extends T> S save(S entity);

    Optional<T> findById(I id);

    boolean existsById(I id);

    long count();
}
