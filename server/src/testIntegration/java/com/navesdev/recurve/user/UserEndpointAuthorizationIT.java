package com.navesdev.recurve.user;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.navesdev.recurve.user.domain.Permission;
import com.navesdev.recurve.user.domain.User;
import com.navesdev.recurve.user.domain.UserSummary;
import com.navesdev.recurve.user.repository.UserRepository;
import com.navesdev.recurve.user.repository.UserSearchRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Crosses every layer: real HTTP Basic against the real service, so it
 * covers what a mocked service cannot — that the route rules in
 * {@code SecurityConfig} run, that MANAGE_* implies VIEW_* (BR-01) and
 * that an inactive operator is refused (BR-09).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserEndpointAuthorizationIT {

    private static final String PASSWORD = "s3cret-password";
    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository repository;

    @Autowired
    private UserSearchRepository searchRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        // The database write rolls back with the test; the index does not,
        // so it is rebuilt from scratch instead.
        searchRepository.recreateIndex();
        entityManager.createNativeQuery("TRUNCATE users CASCADE").executeUpdate();

        // Distinct names on purpose: sorting by name has nothing to say
        // about operators whose names all tie.
        register("Maya Manager", "manager@recurve.local", Set.of(Permission.MANAGE_USERS), true);
        register("Vera Viewer", "viewer@recurve.local", Set.of(Permission.VIEW_USERS), true);
        register("Otto Outsider", "outsider@recurve.local", Set.of(Permission.VIEW_PLANS), true);
        register("Rita Retired", "retired@recurve.local", Set.of(Permission.MANAGE_USERS), false);
        register("Sam Sysadmin", "sysadmin@recurve.local", Set.of(Permission.MANAGE_SYSTEM), true);
        entityManager.flush();
    }

    @Test
    void refusesAnAnonymousRequest() throws Exception {
        mvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
    }

    @Test
    void refusesAWrongPassword() throws Exception {
        mvc.perform(get("/api/users").with(httpBasic("manager@recurve.local", "wrong")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refusesAnInactiveOperator() throws Exception {
        mvc.perform(get("/api/users").with(basic("retired@recurve.local")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void manageImpliesViewSoAManagerMayList() throws Exception {
        mvc.perform(get("/api/users").with(basic("manager@recurve.local")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5));
    }

    @Test
    void aViewerMayListButNotCreate() throws Exception {
        mvc.perform(get("/api/users").with(basic("viewer@recurve.local")))
                .andExpect(status().isOk());

        mvc.perform(post("/api/users")
                .with(basic("viewer@recurve.local"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("new@recurve.local")))
                .andExpect(status().isForbidden());
    }

    @Test
    void anOperatorWithoutUserPermissionsIsRefused() throws Exception {
        mvc.perform(get("/api/users").with(basic("outsider@recurve.local")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Access denied"));
    }

    @Test
    void theRefusalComesBeforeTheRequestIsEvenRead() throws Exception {
        // Authorization sits in the filter chain, ahead of body binding: an
        // operator who may not create a user gets a 403, not a 400 that
        // would tell them what a valid body looks like.
        mvc.perform(post("/api/users")
                .with(basic("viewer@recurve.local"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/users").with(basic("outsider@recurve.local")).param("size", "999"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aManagerCreatesAnOperator() throws Exception {
        mvc.perform(post("/api/users")
                .with(basic("manager@recurve.local"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("new@recurve.local")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("new@recurve.local"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void reportsADuplicateEmailAsABusinessRuleViolation() throws Exception {
        mvc.perform(post("/api/users")
                .with(basic("manager@recurve.local"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("viewer@recurve.local")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void searchesFiltersAndPaginatesInOneRequest() throws Exception {
        mvc.perform(get("/api/users")
                .with(basic("manager@recurve.local"))
                .param("q", "recurve.local")
                .param("filter", "active:true")
                .param("sort", "-email.keyword")
                .param("page", "0")
                .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].email").value("viewer@recurve.local"));
    }

    @Nested
    @DisplayName("FR-01.5 rebuilding the index is a system operation")
    class Reindexing {

        @Test
        void managingOperatorsIsNotEnough() throws Exception {
            mvc.perform(post("/api/users/reindex").with(basic("manager@recurve.local")))
                    .andExpect(status().isForbidden());
        }

        @Test
        void managingTheSystemRebuildsTheIndexFromTheDatabase() throws Exception {
            mvc.perform(post("/api/users/reindex").with(basic("sysadmin@recurve.local")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.indexed").value(5));
        }
    }

    @Nested
    @DisplayName("FR-06 sorting a listing")
    class Ordering {

        @Test
        void aMinusBeforeTheFieldTurnsTheOrderAround() throws Exception {
            List<String> ascending = emailsSortedBy("email.keyword");
            List<String> descending = emailsSortedBy("-email.keyword");

            assertThat(ascending).isSorted();
            assertThat(descending).containsExactlyElementsOf(ascending.reversed());
        }

        @Test
        void theOrderAppliesToWhicheverFieldWasChosen() throws Exception {
            assertThat(emailsSortedBy("name.keyword")).isNotEqualTo(emailsSortedBy("-name.keyword"));
        }

        @Test
        void aFieldWithNoMinusComesBackAscending() throws Exception {
            assertThat(emailsSortedBy("email.keyword")).isSorted();
        }

        @Test
        void aListingWithNoSortComesBackByNameAscending() throws Exception {
            assertThat(emailsSortedBy(null)).containsExactlyElementsOf(emailsSortedBy("name.keyword"));
        }

        @Test
        void aFieldTheIndexRefusesIsABadRequestThatNamesIt() throws Exception {
            // The mapping is the allow-list: nothing before Elasticsearch
            // vets the field, and its refusal comes back with its reason.
            mvc.perform(get("/api/users").with(basic("manager@recurve.local")).param("sort", "permissions"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("permissions")));

            mvc.perform(get("/api/users").with(basic("manager@recurve.local")).param("sort", "email"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("keyword")));
        }

        @Test
        void aMinusWithNoFieldIsAValidationError() throws Exception {
            mvc.perform(get("/api/users").with(basic("manager@recurve.local")).param("sort", "-"))
                    .andExpect(status().isBadRequest());
        }

        private List<String> emailsSortedBy(String sort) throws Exception {
            var request = get("/api/users").with(basic("manager@recurve.local"));
            if (sort != null) {
                request = request.param("sort", sort);
            }

            String json = mvc.perform(request)
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            return JsonPath.read(json, "$.items[*].email");
        }
    }

    private static String body(String email) {
        return """
                {"name":"New Operator","email":"%s","password":"%s","permissions":["VIEW_PLANS"]}
                """.formatted(email, PASSWORD);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor basic(String email) {
        return httpBasic(email, PASSWORD);
    }

    private void register(String name, String email, Set<Permission> permissions, boolean active) {
        User operator = User.create(name, email, passwordEncoder.encode(PASSWORD), permissions, NOW);
        if (!active) {
            operator.deactivate();
        }
        searchRepository.save(UserSummary.of(repository.save(operator)));
    }
}
