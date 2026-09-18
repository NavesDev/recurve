package com.navesdev.recurve.user.service;

import java.time.Clock;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.navesdev.recurve.user.domain.User;
import com.navesdev.recurve.user.domain.exception.EmailAlreadyInUseException;
import com.navesdev.recurve.user.domain.exception.UserNotFoundException;
import com.navesdev.recurve.user.repository.UserRepository;
import com.navesdev.recurve.user.repository.UserSpecifications;

import lombok.RequiredArgsConstructor;

/**
 * The feature's single entry point. One method per use case: it loads,
 * calls the domain and persists, holding no business rule of its own —
 * only the rules that need the database, such as email uniqueness.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class UserService {

    private final UserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    @PreAuthorize("hasAuthority('MANAGE_USERS')")
    public User create(CreateUserCommand command) {
        String email = normalize(command.email());

        if (repository.existsByEmail(email)) {
            throw new EmailAlreadyInUseException(email);
        }

        User user = User.create(
                command.name(),
                email,
                passwordEncoder.encode(command.rawPassword()),
                command.permissions(),
                clock.instant());

        return repository.save(user);
    }

    @PreAuthorize("hasAuthority('MANAGE_USERS')")
    public User update(UpdateUserCommand command) {
        User user = findOrThrow(command.id());
        String email = normalize(command.email());

        if (repository.existsByEmailAndIdNot(email, command.id())) {
            throw new EmailAlreadyInUseException(email);
        }

        user.rename(command.name());
        user.changeEmail(email);
        user.replacePermissions(command.permissions());

        return repository.save(user);
    }

    /** FR-01.3: deactivate without deleting. */
    @PreAuthorize("hasAuthority('MANAGE_USERS')")
    public User deactivate(UUID id) {
        User user = findOrThrow(id);
        user.deactivate();
        return repository.save(user);
    }

    @PreAuthorize("hasAuthority('VIEW_USERS')")
    @Transactional(readOnly = true)
    public User findById(UUID id) {
        return findOrThrow(id);
    }

    /** FR-06.1 and FR-07: search and filter, then sort, then paginate. */
    @PreAuthorize("hasAuthority('VIEW_USERS')")
    @Transactional(readOnly = true)
    public Page<User> search(UserFilter filter, Pageable pageable) {
        return repository.findAll(UserSpecifications.from(filter), pageable);
    }

    private User findOrThrow(UUID id) {
        return repository.findById(id).orElseThrow(() -> new UserNotFoundException(id));
    }

    private static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }
}
