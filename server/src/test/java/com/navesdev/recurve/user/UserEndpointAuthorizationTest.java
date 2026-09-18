package com.navesdev.recurve.user;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
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
import com.navesdev.recurve.user.repository.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Crosses every layer: real HTTP Basic against the real service, so it
 * covers what a mocked service cannot — that {@code @PreAuthorize} runs,
 * that MANAGE_* implies VIEW_* (BR-01) and that an inactive operator is
 * refused (BR-09).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserEndpointAuthorizationTest {

    private static final String PASSWORD = "s3cret-password";
    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository repository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        entityManager.createNativeQuery("TRUNCATE users CASCADE").executeUpdate();

        register("manager@recurve.local", Set.of(Permission.MANAGE_USERS), true);
        register("viewer@recurve.local", Set.of(Permission.VIEW_USERS), true);
        register("outsider@recurve.local", Set.of(Permission.VIEW_PLANS), true);
        register("retired@recurve.local", Set.of(Permission.MANAGE_USERS), false);
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
                .andExpect(jsonPath("$.total").value(4));
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
                .param("sort", "email")
                .param("direction", "desc")
                .param("page", "0")
                .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].email").value("viewer@recurve.local"));
    }

    private static String body(String email) {
        return """
                {"name":"New Operator","email":"%s","password":"%s","permissions":["VIEW_PLANS"]}
                """.formatted(email, PASSWORD);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor basic(String email) {
        return httpBasic(email, PASSWORD);
    }

    private void register(String email, Set<Permission> permissions, boolean active) {
        User operator = User.create("Operator", email, passwordEncoder.encode(PASSWORD), permissions, NOW);
        if (!active) {
            operator.deactivate();
        }
        repository.save(operator);
    }
}
