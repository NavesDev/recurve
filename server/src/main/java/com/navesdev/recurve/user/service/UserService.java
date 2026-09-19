package com.navesdev.recurve.user.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.navesdev.recurve.shared.service.SearchFilter;
import com.navesdev.recurve.user.domain.User;
import com.navesdev.recurve.user.domain.UserValidator;
import com.navesdev.recurve.user.domain.UserSummary;
import com.navesdev.recurve.user.domain.exception.EmailAlreadyInUseException;
import com.navesdev.recurve.user.domain.exception.UserNotFoundException;
import com.navesdev.recurve.user.repository.UserRepository;
import com.navesdev.recurve.user.repository.UserSearchRepository;

import lombok.RequiredArgsConstructor;

/**
 * The feature's single entry point. One method per use case: it loads,
 * calls the domain and persists, holding no business rule of its own —
 * only the rules that need the database, such as email uniqueness.
 *
 * <p>Two stores: PostgreSQL holds the operator and is the source of truth;
 * Elasticsearch holds the read model the listing is served from (FR-06,
 * FR-07). Every write goes to both inside the same transaction, so that a
 * failure to index rolls the write back — fail-fast, never a silent
 * divergence. The index can always be rebuilt from the database.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class UserService {

    private static final int REINDEX_BATCH = 500;

    private final UserRepository repository;
    private final UserSearchRepository searchRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public User create(CreateUserCommand command) {
        String email = UserValidator.email(command.email());

        if (repository.existsByEmail(email)) {
            throw new EmailAlreadyInUseException(email);
        }

        User user = User.create(
                command.name(),
                email,
                passwordEncoder.encode(command.rawPassword()),
                command.permissions(),
                clock.instant());

        return persist(user);
    }

    public User update(UpdateUserCommand command) {
        User user = findOrThrow(command.id());
        String email = UserValidator.email(command.email());

        if (repository.existsByEmailAndIdNot(email, command.id())) {
            throw new EmailAlreadyInUseException(email);
        }

        user.rename(command.name());
        user.changeEmail(email);
        user.replacePermissions(command.permissions());

        return persist(user);
    }

    /** FR-01.3: deactivate without deleting. */
    public User deactivate(UUID id) {
        User user = findOrThrow(id);
        user.deactivate();
        return persist(user);
    }

    @Transactional(readOnly = true)
    public User findById(UUID id) {
        return findOrThrow(id);
    }

    /** FR-06.1 and FR-07: search and filter, then sort, then paginate — all in the index. */
    @Transactional(readOnly = true)
    public Page<UserSummary> search(SearchFilter filter, Pageable pageable) {
        return searchRepository.search(filter, pageable);
    }

    /**
     * FR-01.5: rebuild the index from the database. Returns how many
     * operators were indexed. Recreating the index rather than overwriting
     * documents is what drops a document whose operator no longer exists.
     * {@link UserIndexBootstrap} runs it at startup, the reindex endpoint
     * on request; neither needs a permission here — see
     * {@code SecurityConfig}.
     */
    public long reindex() {
        searchRepository.recreateIndex();

        long indexed = 0;
        List<UserSummary> batch = new ArrayList<>(REINDEX_BATCH);
        try (Stream<User> users = repository.streamAll()) {
            for (User user : (Iterable<User>) users::iterator) {
                batch.add(UserSummary.of(user));
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

    /** Step 3 of every write: the database first, then the index that mirrors it. */
    private User persist(User user) {
        User saved = repository.save(user);
        searchRepository.save(UserSummary.of(saved));
        return saved;
    }

    private User findOrThrow(UUID id) {
        return repository.findById(id).orElseThrow(() -> new UserNotFoundException(id));
    }
}
