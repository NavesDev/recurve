package com.navesdev.recurve.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.navesdev.recurve.user.domain.Permission;
import com.navesdev.recurve.user.domain.User;
import com.navesdev.recurve.user.domain.exception.EmailAlreadyInUseException;
import com.navesdev.recurve.user.domain.exception.UserNotFoundException;
import com.navesdev.recurve.user.repository.UserRepository;

/**
 * The rules that need the database to be decided, which is why they live
 * in the service and not in the entity.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final String RAW_PASSWORD = "s3cret-password";
    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    @Mock
    private UserRepository repository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private UserService service;

    @BeforeEach
    void setUp() {
        service = new UserService(repository, passwordEncoder, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Nested
    @DisplayName("BR-02 the operator email is unique")
    class UniqueEmail {

        @Test
        void anEmailAlreadyInUseIsRefused() {
            when(repository.existsByEmail("ada@recurve.local")).thenReturn(true);

            assertThatThrownBy(() -> service.create(command("ada@recurve.local")))
                    .isInstanceOf(EmailAlreadyInUseException.class);
        }

        @Test
        void theClashIsDetectedRegardlessOfTheCaseTheEmailWasTypedIn() {
            when(repository.existsByEmail("ada@recurve.local")).thenReturn(true);

            assertThatThrownBy(() -> service.create(command("ADA@Recurve.Local")))
                    .isInstanceOf(EmailAlreadyInUseException.class);
        }

        @Test
        void nothingIsRegisteredWhenTheEmailIsRefused() {
            when(repository.existsByEmail(anyString())).thenReturn(true);

            assertThatThrownBy(() -> service.create(command("ada@recurve.local")))
                    .isInstanceOf(EmailAlreadyInUseException.class);

            verify(repository, never()).save(any());
        }

        @Test
        void anOperatorKeepingItsOwnEmailIsNotAClash() {
            UUID id = UUID.randomUUID();
            when(repository.findById(id)).thenReturn(Optional.of(existingOperator()));
            when(repository.existsByEmailAndIdNot("ada@recurve.local", id)).thenReturn(false);
            when(repository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

            User updated = service.update(new UpdateUserCommand(id, "Ada Lovelace",
                    "ada@recurve.local", Set.of(Permission.MANAGE_PLANS)));

            assertThat(updated.getName()).isEqualTo("Ada Lovelace");
        }

        @Test
        void takingAnotherOperatorsEmailIsRefused() {
            UUID id = UUID.randomUUID();
            when(repository.findById(id)).thenReturn(Optional.of(existingOperator()));
            when(repository.existsByEmailAndIdNot("grace@recurve.local", id)).thenReturn(true);

            assertThatThrownBy(() -> service.update(
                    new UpdateUserCommand(id, "Ada", "grace@recurve.local", Set.of())))
                    .isInstanceOf(EmailAlreadyInUseException.class);

            verify(repository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("NFR-04 the password is stored as a hash")
    class PasswordStorage {

        @Test
        void theRawPasswordIsNeverWhatGetsStored() {
            when(repository.existsByEmail(anyString())).thenReturn(false);
            when(passwordEncoder.encode(RAW_PASSWORD)).thenReturn("$2a$10$hash");
            when(repository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

            service.create(command("ada@recurve.local"));

            ArgumentCaptor<User> stored = ArgumentCaptor.forClass(User.class);
            verify(repository).save(stored.capture());

            assertThat(stored.getValue().getPasswordHash())
                    .isNotEqualTo(RAW_PASSWORD)
                    .isEqualTo("$2a$10$hash");
        }

        @Test
        void aRefusedRegistrationNeverHashesAnything() {
            when(repository.existsByEmail(anyString())).thenReturn(true);

            assertThatThrownBy(() -> service.create(command("ada@recurve.local")))
                    .isInstanceOf(EmailAlreadyInUseException.class);

            verify(passwordEncoder, never()).encode(anyString());
        }
    }

    @Nested
    @DisplayName("FR-01.3 an operator is deactivated, never deleted")
    class Deactivating {

        @Test
        void aDeactivatedOperatorRemainsOnRecord() {
            UUID id = UUID.randomUUID();
            when(repository.findById(id)).thenReturn(Optional.of(existingOperator()));
            when(repository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

            User deactivated = service.deactivate(id);

            assertThat(deactivated.isActive()).isFalse();
            assertThat(deactivated.getEmail()).isEqualTo("ada@recurve.local");
            verify(repository).save(any(User.class));
        }
    }

    @Nested
    @DisplayName("An operator that does not exist")
    class Missing {

        @Test
        void cannotBeRead() {
            UUID id = UUID.randomUUID();
            when(repository.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.findById(id)).isInstanceOf(UserNotFoundException.class);
        }

        @Test
        void cannotBeUpdated() {
            UUID id = UUID.randomUUID();
            when(repository.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.update(
                    new UpdateUserCommand(id, "Ada", "ada@recurve.local", Set.of())))
                    .isInstanceOf(UserNotFoundException.class);
        }

        @Test
        void cannotBeDeactivated() {
            UUID id = UUID.randomUUID();
            when(repository.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.deactivate(id)).isInstanceOf(UserNotFoundException.class);
        }
    }

    private static CreateUserCommand command(String email) {
        return new CreateUserCommand("Ada", email, RAW_PASSWORD, Set.of(Permission.VIEW_PLANS));
    }

    private static User existingOperator() {
        return User.create("Ada", "ada@recurve.local", "$2a$10$hash",
                Set.of(Permission.VIEW_USERS), NOW);
    }
}
