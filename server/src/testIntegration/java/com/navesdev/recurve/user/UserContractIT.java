package com.navesdev.recurve.user;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.navesdev.recurve.user.domain.Permission;
import com.navesdev.recurve.user.domain.User;
import com.navesdev.recurve.user.domain.UserSummary;
import com.navesdev.recurve.user.repository.UserRepository;
import com.navesdev.recurve.user.repository.UserSearchRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * The contract is written by hand, so the only thing that proves it
 * describes this server is a real exchange checked against it: every
 * request and response here must be one {@code docs/openapi.yaml} allows.
 * Behaviour itself is covered elsewhere; this asks only whether what went
 * over the wire matches what was promised.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserContractIT {

    private static final String PASSWORD = "s3cret-password";
    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    private static final String SPEC = "/docs/openapi.yaml";

    /** Request and response both as promised. */
    private static final ResultMatcher CONTRACT = openApi()
            .isValid(OpenApiInteractionValidator.createForSpecificationUrl(SPEC).build());

    /** For a request that is wrong on purpose: only the refusal is checked. */
    private static final ResultMatcher CONTRACT_RESPONSE = openApi()
            .isValid(OpenApiInteractionValidator.createForSpecificationUrl(SPEC)
                    .withLevelResolver(LevelResolver.create()
                            .withLevel("validation.request", ValidationReport.Level.IGNORE)
                            .build())
                    .build());

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

    private UUID viewerId;

    @BeforeEach
    void setUp() {
        searchRepository.recreateIndex();
        entityManager.createNativeQuery("TRUNCATE users CASCADE").executeUpdate();

        register("Maya Manager", "manager@recurve.local", Set.of(Permission.MANAGE_USERS, Permission.MANAGE_SYSTEM));
        viewerId = register("Vera Viewer", "viewer@recurve.local", Set.of(Permission.VIEW_PLANS));
        entityManager.flush();
    }

    @Test
    void createsAnOperator() throws Exception {
        mvc.perform(post("/api/users").with(manager())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Ada Lovelace","email":"ada@recurve.local","password":"%s","permissions":["VIEW_PLANS"]}
                        """.formatted(PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(CONTRACT);
    }

    @Test
    void refusesAnInvalidBody() throws Exception {
        mvc.perform(post("/api/users").with(manager())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"","email":"not-an-email","password":"short"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(CONTRACT_RESPONSE);
    }

    @Test
    void refusesADuplicateEmail() throws Exception {
        mvc.perform(post("/api/users").with(manager())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Twin","email":"viewer@recurve.local","password":"%s"}
                        """.formatted(PASSWORD)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(CONTRACT);
    }

    @Test
    void findsAnOperator() throws Exception {
        mvc.perform(get("/api/users/{id}", viewerId).with(manager()))
                .andExpect(status().isOk())
                .andExpect(CONTRACT);
    }

    @Test
    void reportsAMissingOperator() throws Exception {
        mvc.perform(get("/api/users/{id}", UUID.randomUUID()).with(manager()))
                .andExpect(status().isNotFound())
                .andExpect(CONTRACT);
    }

    @Test
    void updatesAnOperator() throws Exception {
        mvc.perform(put("/api/users/{id}", viewerId).with(manager())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Vera Viewer","email":"vera@recurve.local","permissions":["VIEW_PLANS","VIEW_SUBSCRIBERS"]}
                        """))
                .andExpect(status().isOk())
                .andExpect(CONTRACT);
    }

    @Test
    void deactivatesAnOperatorOnce() throws Exception {
        mvc.perform(delete("/api/users/{id}", viewerId).with(manager()))
                .andExpect(status().isOk())
                .andExpect(CONTRACT);

        mvc.perform(delete("/api/users/{id}", viewerId).with(manager()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(CONTRACT);
    }

    @Test
    void listsOperators() throws Exception {
        mvc.perform(get("/api/users").with(manager())
                .param("q", "recurve")
                .param("filter", "active:true")
                .param("sort", "email.keyword:desc")
                .param("page", "0")
                .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(CONTRACT);
    }

    @Test
    void refusesAListingOutOfBounds() throws Exception {
        mvc.perform(get("/api/users").with(manager()).param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(CONTRACT_RESPONSE);
    }

    @Test
    void refusesWrongCredentials() throws Exception {
        mvc.perform(get("/api/users").with(httpBasic("manager@recurve.local", "wrong")))
                .andExpect(status().isUnauthorized())
                .andExpect(CONTRACT_RESPONSE);
    }

    @Test
    void refusesAMissingPermission() throws Exception {
        mvc.perform(post("/api/users/reindex").with(httpBasic("viewer@recurve.local", PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(CONTRACT);
    }

    @Test
    void rebuildsTheIndex() throws Exception {
        mvc.perform(post("/api/users/reindex").with(manager()))
                .andExpect(status().isOk())
                .andExpect(CONTRACT);
    }

    private static RequestPostProcessor manager() {
        return httpBasic("manager@recurve.local", PASSWORD);
    }

    private UUID register(String name, String email, Set<Permission> permissions) {
        User operator = repository.save(User.create(name, email, passwordEncoder.encode(PASSWORD), permissions, NOW));
        searchRepository.save(UserSummary.of(operator));
        return operator.getId();
    }
}
