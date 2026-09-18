package com.navesdev.recurve.user.service;

import java.time.Clock;
import java.util.EnumSet;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.navesdev.recurve.user.domain.Permission;
import com.navesdev.recurve.user.domain.User;
import com.navesdev.recurve.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Creates the first operator when the table is empty.
 *
 * <p>Every write use case demands {@code MANAGE_USERS}, so on an empty
 * database there is nobody who could create the first operator through the
 * API. Credentials come from the environment (NFR-03) and nothing is
 * created when they are absent.
 *
 * <p>Internal by design, like {@link OperatorDetailsService}: it runs at
 * startup with no authenticated operator, so it talks to the repository
 * rather than to {@link UserService}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OperatorBootstrap implements ApplicationRunner {

    private final UserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    @Value("${recurve.bootstrap.admin.email:}")
    private String email;

    @Value("${recurve.bootstrap.admin.password:}")
    private String password;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (email.isBlank() || password.isBlank()) {
            log.info("No bootstrap operator configured; skipping.");
            return;
        }

        if (repository.count() > 0) {
            return;
        }

        User admin = User.create(
                "Administrator",
                email,
                passwordEncoder.encode(password),
                EnumSet.allOf(Permission.class),
                clock.instant());

        repository.save(admin);
        log.info("Bootstrap operator created with email {}", admin.getEmail());
    }
}
