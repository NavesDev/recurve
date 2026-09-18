package com.navesdev.recurve.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

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

    @Test
    void hashesThePasswordBeforeSaving() {
        when(repository.existsByEmail("ada@recurve.local")).thenReturn(false);
        when(passwordEncoder.encode("s3cret-password")).thenReturn("hashed");
        when(repository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.create(new CreateUserCommand("Ada", "ada@recurve.local", "s3cret-password",
                Set.of(Permission.VIEW_PLANS)));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(repository).save(saved.capture());

        assertThat(saved.getValue().getPasswordHash()).isEqualTo("hashed");
        assertThat(saved.getValue().getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void refusesAnEmailThatIsAlreadyTaken() {
        when(repository.existsByEmail("ada@recurve.local")).thenReturn(true);

        assertThatThrownBy(() -> service.create(
                new CreateUserCommand("Ada", "Ada@Recurve.local", "s3cret-password", Set.of())))
                .isInstanceOf(EmailAlreadyInUseException.class);

        verify(repository, never()).save(any());
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void lettingAnOperatorKeepItsOwnEmailOnUpdate() {
        UUID id = UUID.randomUUID();
        User existing = existingUser();
        when(repository.findById(id)).thenReturn(Optional.of(existing));
        when(repository.existsByEmailAndIdNot("ada@recurve.local", id)).thenReturn(false);
        when(repository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User updated = service.update(new UpdateUserCommand(id, "Ada Lovelace", "ada@recurve.local",
                Set.of(Permission.MANAGE_PLANS)));

        assertThat(updated.getName()).isEqualTo("Ada Lovelace");
        assertThat(updated.getPermissions()).containsExactly(Permission.MANAGE_PLANS);
    }

    @Test
    void refusesAnUpdateOntoAnotherOperatorsEmail() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(existingUser()));
        when(repository.existsByEmailAndIdNot(eq("taken@recurve.local"), eq(id))).thenReturn(true);

        assertThatThrownBy(() -> service.update(
                new UpdateUserCommand(id, "Ada", "taken@recurve.local", Set.of())))
                .isInstanceOf(EmailAlreadyInUseException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void deactivatesWithoutDeleting() {
        UUID id = UUID.randomUUID();
        User existing = existingUser();
        when(repository.findById(id)).thenReturn(Optional.of(existing));
        when(repository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User result = service.deactivate(id);

        assertThat(result.isActive()).isFalse();
        verify(repository, never()).delete(any(User.class));
    }

    @Test
    void reportsAnUnknownOperator() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(id)).isInstanceOf(UserNotFoundException.class);
    }

    private static User existingUser() {
        return User.create("Ada", "ada@recurve.local", "hash", Set.of(Permission.VIEW_USERS), NOW);
    }
}
