package com.navesdev.recurve.user.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.navesdev.recurve.user.domain.Permission;
import com.navesdev.recurve.user.domain.exception.InvalidUserException;
import com.navesdev.recurve.user.domain.User;
import com.navesdev.recurve.user.repository.UserRepository;
import com.navesdev.recurve.user.repository.UserSearchRepository;

@ExtendWith(MockitoExtension.class)
class OperatorBootstrapTest {

    @Mock
    private UserRepository repository;

    @Mock
    private UserSearchRepository searchRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private OperatorBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-15T10:00:00Z"), ZoneOffset.UTC);
        bootstrap = new OperatorBootstrap(repository, searchRepository, passwordEncoder, clock);
    }

    @Nested
    @DisplayName("The first operator is created from the environment")
    class FirstOperator {

        @Test
        void theBootstrapOperatorIsIndexedLikeAnyOther() {
            configure("admin@recurve.local", "s3cret-password");
            when(repository.count()).thenReturn(0L);
            when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$hash");
            when(repository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

            bootstrap.run(null);

            verify(searchRepository).save(argThat(summary ->
                    summary.email().equals("admin@recurve.local")
                            && summary.permissions().contains(Permission.MANAGE_SYSTEM)));
        }

        @Test
        void nothingIsCreatedOrIndexedWhenAnOperatorAlreadyExists() {
            configure("admin@recurve.local", "s3cret-password");
            when(repository.count()).thenReturn(1L);

            bootstrap.run(null);

            verifyNoInteractions(searchRepository);
        }

        @Test
        void nothingIsCreatedOrIndexedWithoutCredentials() {
            configure("", "");

            bootstrap.run(null);

            verifyNoInteractions(repository, searchRepository);
        }

        @Test
        void aMalformedEmailFromTheEnvironmentFailsStartupInsteadOfBeingStored() {
            // The bootstrap never passes the API's edge, so the domain is
            // the only thing standing between a typo and a stored operator.
            configure("admin", "s3cret-password");

            assertThatThrownBy(() -> bootstrap.run(null)).isInstanceOf(InvalidUserException.class);

            verifyNoInteractions(searchRepository);
        }
    }

    private void configure(String email, String password) {
        ReflectionTestUtils.setField(bootstrap, "email", email);
        ReflectionTestUtils.setField(bootstrap, "password", password);
    }
}
