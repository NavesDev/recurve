package com.navesdev.recurve.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.navesdev.recurve.shared.service.SearchFilter;
import com.navesdev.recurve.user.domain.Permission;
import com.navesdev.recurve.user.domain.User;
import com.navesdev.recurve.user.domain.UserSummary;
import com.navesdev.recurve.user.domain.exception.EmailAlreadyInUseException;
import com.navesdev.recurve.user.domain.exception.UserNotFoundException;
import com.navesdev.recurve.user.repository.UserRepository;
import com.navesdev.recurve.user.repository.UserSearchRepository;

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
    private UserSearchRepository searchRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private UserService service;

    @BeforeEach
    void setUp() {
        service = new UserService(repository, searchRepository, passwordEncoder, Clock.fixed(NOW, ZoneOffset.UTC));
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

    @Nested
    @DisplayName("FR-06 the listing is served from the search index")
    class Indexing {

        @Test
        void aRegisteredOperatorIsIndexedAsItWasSaved() {
            when(repository.existsByEmail(anyString())).thenReturn(false);
            when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$hash");
            when(repository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

            User created = service.create(command("ada@recurve.local"));

            verify(searchRepository).save(argThat(summary ->
                    summary.id().equals(created.getId()) && summary.email().equals("ada@recurve.local")));
        }

        @Test
        void anUpdatedOperatorIsReindexed() {
            UUID id = UUID.randomUUID();
            when(repository.findById(id)).thenReturn(Optional.of(existingOperator()));
            when(repository.existsByEmailAndIdNot(anyString(), any())).thenReturn(false);
            when(repository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

            service.update(new UpdateUserCommand(id, "Ada Lovelace", "ada@recurve.local", Set.of()));

            verify(searchRepository).save(argThat(summary -> summary.name().equals("Ada Lovelace")));
        }

        @Test
        void aDeactivatedOperatorIsReindexedAsInactive() {
            UUID id = UUID.randomUUID();
            when(repository.findById(id)).thenReturn(Optional.of(existingOperator()));
            when(repository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

            service.deactivate(id);

            verify(searchRepository).save(argThat(summary -> !summary.active()));
        }

        @Test
        void anIndexingFailureFailsTheWrite() {
            // Fail-fast: the caller learns that nothing is consistent, and
            // the container rolls the database write back (UserServiceIT).
            when(repository.existsByEmail(anyString())).thenReturn(false);
            when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$hash");
            when(repository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));
            doThrow(new DataAccessResourceFailureException("search node down"))
                    .when(searchRepository).save(any(UserSummary.class));

            assertThatThrownBy(() -> service.create(command("ada@recurve.local")))
                    .isInstanceOf(DataAccessResourceFailureException.class);
        }

        @Test
        void theListingAsksTheIndexAndNeverTheDatabase() {
            SearchFilter filter = SearchFilter.of("ada");
            Pageable page = PageRequest.of(0, 20);
            Page<UserSummary> expected = new PageImpl<>(List.of());
            when(searchRepository.search(filter, page)).thenReturn(expected);

            assertThat(service.search(filter, page)).isSameAs(expected);
            verifyNoInteractions(repository);
        }
    }

    @Nested
    @DisplayName("FR-01.5 the index is rebuilt from the database")
    class Reindexing {

        @Test
        void theIndexIsRecreatedAndEveryOperatorIndexed() {
            User ada = User.create("Ada", "ada@recurve.local", "$2a$10$hash", Set.of(), NOW);
            User grace = User.create("Grace", "grace@recurve.local", "$2a$10$hash", Set.of(), NOW);
            when(repository.streamAll()).thenReturn(Stream.of(ada, grace));

            long indexed = service.reindex();

            assertThat(indexed).isEqualTo(2);
            verify(searchRepository).recreateIndex();
            verify(searchRepository).saveAll(argThat(batch -> batch.size() == 2));
            verify(searchRepository).refresh();
        }

        @Test
        void anEmptyDatabaseStillLeavesAFreshIndexBehind() {
            when(repository.streamAll()).thenReturn(Stream.empty());

            assertThat(service.reindex()).isZero();
            verify(searchRepository).recreateIndex();
            verify(searchRepository, never()).saveAll(any());
        }
    }

    private static CreateUserCommand command(String email) {
        return new CreateUserCommand("Ada", email, RAW_PASSWORD, Set.of(Permission.VIEW_PLANS));
    }

    private static User existingOperator() {
        return User.create("Ada", "ada@recurve.local", "$2a$10$hash",
                Set.of(Permission.VIEW_PLANS), NOW);
    }
}
