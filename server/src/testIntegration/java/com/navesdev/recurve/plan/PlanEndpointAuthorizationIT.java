package com.navesdev.recurve.plan;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.navesdev.recurve.plan.domain.BillingInterval;
import com.navesdev.recurve.plan.domain.Plan;
import com.navesdev.recurve.plan.domain.PlanSummary;
import com.navesdev.recurve.plan.repository.PlanRepository;
import com.navesdev.recurve.plan.repository.PlanSearchRepository;
import com.navesdev.recurve.user.domain.Permission;
import com.navesdev.recurve.user.domain.User;
import com.navesdev.recurve.user.repository.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * The plan route rules in {@code SecurityConfig}, over real HTTP Basic and
 * the real service: VIEW_PLANS reads, MANAGE_PLANS writes and reads too
 * (BR-01), rebuilding the index is a system operation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PlanEndpointAuthorizationIT {

    private static final String PASSWORD = "s3cret-password";
    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private PlanSearchRepository planSearchRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @PersistenceContext
    private EntityManager entityManager;

    private UUID planId;
    private UUID priceId;

    @BeforeEach
    void setUp() {
        planSearchRepository.recreateIndex();
        entityManager.createNativeQuery("TRUNCATE users CASCADE").executeUpdate();
        entityManager.createNativeQuery("TRUNCATE plans CASCADE").executeUpdate();

        register("viewer@recurve.local", Permission.VIEW_PLANS);
        register("manager@recurve.local", Permission.MANAGE_PLANS);
        register("outsider@recurve.local", Permission.VIEW_SUBSCRIBERS);
        register("sysadmin@recurve.local", Permission.MANAGE_SYSTEM);

        Plan plan = Plan.create("Pro", null, NOW);
        priceId = plan.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW).getId();
        planId = planSearchRepository.save(PlanSummary.of(planRepository.save(plan))).id();
        entityManager.flush();
    }

    @Nested
    @DisplayName("VIEW_PLANS reads and nothing else")
    class Viewer {

        @Test
        void listsAndReadsPlans() throws Exception {
            mvc.perform(get("/api/plans").with(as("viewer"))).andExpect(status().isOk());
            mvc.perform(get("/api/plans/{id}", planId).with(as("viewer"))).andExpect(status().isOk());
        }

        @Test
        void changesNothing() throws Exception {
            mvc.perform(post("/api/plans").with(as("viewer")).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Basic\"}")).andExpect(status().isForbidden());
            mvc.perform(put("/api/plans/{id}", planId).with(as("viewer")).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Basic\"}")).andExpect(status().isForbidden());
            mvc.perform(delete("/api/plans/{id}", planId).with(as("viewer"))).andExpect(status().isForbidden());
            mvc.perform(post("/api/plans/{id}/prices", planId).with(as("viewer")).contentType(MediaType.APPLICATION_JSON)
                    .content(priceBody("YEARLY"))).andExpect(status().isForbidden());
            mvc.perform(post("/api/prices/{id}/replace", priceId).with(as("viewer")).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"price\":59.90}")).andExpect(status().isForbidden());
            mvc.perform(delete("/api/prices/{id}", priceId).with(as("viewer"))).andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("MANAGE_PLANS writes, and reads because manage implies view (BR-01)")
    class Manager {

        @Test
        void listsPlans() throws Exception {
            mvc.perform(get("/api/plans").with(as("manager")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1));
        }

        @Test
        void registersAPlan() throws Exception {
            mvc.perform(post("/api/plans").with(as("manager")).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Basic\"}"))
                    .andExpect(status().isCreated());
        }

        @Test
        void pricesReplacesAndDeactivates() throws Exception {
            mvc.perform(post("/api/plans/{id}/prices", planId).with(as("manager")).contentType(MediaType.APPLICATION_JSON)
                    .content(priceBody("YEARLY")))
                    .andExpect(status().isCreated());
            mvc.perform(post("/api/prices/{id}/replace", priceId).with(as("manager")).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"price\":59.90}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.prices.length()").value(3));
            mvc.perform(delete("/api/prices/{id}", priceId).with(as("manager")))
                    .andExpect(status().isUnprocessableEntity());
        }

        @Test
        void aSecondActivePriceOnOnePairIsABusinessRuleViolation() throws Exception {
            mvc.perform(post("/api/plans/{id}/prices", planId).with(as("manager")).contentType(MediaType.APPLICATION_JSON)
                    .content(priceBody("MONTHLY")))
                    .andExpect(status().isUnprocessableEntity());
        }

        @Test
        void filtersByCycleEndToEnd() throws Exception {
            mvc.perform(get("/api/plans").with(as("manager")).param("filter", "activeIntervals:YEARLY"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0));
            mvc.perform(get("/api/plans").with(as("manager")).param("filter", "activeIntervals:MONTHLY"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1));
        }
    }

    @Test
    void anOperatorWithNoPlanPermissionIsRefusedEvenAListing() throws Exception {
        mvc.perform(get("/api/plans").with(as("outsider"))).andExpect(status().isForbidden());
    }

    @Nested
    @DisplayName("Rebuilding the plan index is a system operation")
    class Reindexing {

        @Test
        void managingPlansIsNotEnough() throws Exception {
            mvc.perform(post("/api/plans/reindex").with(as("manager"))).andExpect(status().isForbidden());
        }

        @Test
        void managingTheSystemRebuildsTheIndexFromTheDatabase() throws Exception {
            mvc.perform(post("/api/plans/reindex").with(as("sysadmin")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.indexed").value(1));
        }
    }

    private static String priceBody(String interval) {
        return """
                {"price":49.90,"currency":"BRL","interval":"%s"}
                """.formatted(interval);
    }

    private static RequestPostProcessor as(String who) {
        return httpBasic(who + "@recurve.local", PASSWORD);
    }

    private void register(String email, Permission permission) {
        userRepository.save(User.create(email, email, passwordEncoder.encode(PASSWORD), Set.of(permission), NOW));
    }
}
