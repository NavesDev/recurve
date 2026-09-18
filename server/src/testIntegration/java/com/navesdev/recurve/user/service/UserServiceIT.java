package com.navesdev.recurve.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.navesdev.recurve.user.domain.Permission;
import com.navesdev.recurve.user.domain.UserSummary;
import com.navesdev.recurve.user.repository.UserRepository;
import com.navesdev.recurve.user.repository.UserSearchRepository;

/**
 * Fail-fast across the transaction boundary: what a unit test cannot
 * show is that the container rolls the database write back when the
 * index refuses it. Deliberately not {@code @Transactional}: a test
 * transaction would hide the rollback it is here to observe. No cleanup
 * either — when the rule holds, nothing was written; every other IT
 * truncates the table before it starts.
 */
@SpringBootTest
class UserServiceIT {

    @Autowired
    private UserService service;

    @Autowired
    private UserRepository repository;

    @MockitoBean
    private UserSearchRepository searchRepository;

    @Nested
    @DisplayName("Fail-fast: the database and the index never diverge")
    class NeverDiverge {

        @Test
        @WithMockUser(authorities = "MANAGE_USERS")
        void aWriteTheIndexRefusesLeavesNothingInTheDatabase() {
            doThrow(new DataAccessResourceFailureException("search node down"))
                    .when(searchRepository).save(any(UserSummary.class));

            assertThatThrownBy(() -> service.create(new CreateUserCommand(
                    "Ada Lovelace", "ada@recurve.local", "s3cret-password", Set.of(Permission.VIEW_USERS))))
                    .isInstanceOf(DataAccessResourceFailureException.class);

            assertThat(repository.existsByEmail("ada@recurve.local")).isFalse();
        }
    }
}
