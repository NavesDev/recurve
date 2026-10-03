package com.navesdev.recurve.plan;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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
 * Every real plan and price exchange, request and response, must be one
 * {@code docs/openapi.yaml} allows. Behaviour is covered elsewhere; this
 * asks only whether what went over the wire matches what was promised.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PlanContractIT {

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

        userRepository.save(User.create("Maya Manager", "manager@recurve.local", passwordEncoder.encode(PASSWORD),
                Set.of(Permission.MANAGE_PLANS, Permission.MANAGE_SYSTEM), NOW));
        userRepository.save(User.create("Vera Viewer", "viewer@recurve.local", passwordEncoder.encode(PASSWORD),
                Set.of(Permission.VIEW_PLANS), NOW));

        Plan plan = Plan.create("Pro", "For teams", NOW);
        priceId = plan.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW).getId();
        planId = planSearchRepository.save(PlanSummary.of(planRepository.save(plan))).id();
        entityManager.flush();
    }

    @Test
    void createsAPlan() throws Exception {
        mvc.perform(post("/api/plans").with(manager()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Basic\",\"description\":null}"))
                .andExpect(status().isCreated())
                .andExpect(CONTRACT);
    }

    @Test
    void refusesAnInvalidBody() throws Exception {
        mvc.perform(post("/api/plans").with(manager()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(CONTRACT_RESPONSE);
    }

    @Test
    void findsAPlan() throws Exception {
        mvc.perform(get("/api/plans/{id}", planId).with(manager()))
                .andExpect(status().isOk())
                .andExpect(CONTRACT);
    }

    @Test
    void reportsAMissingPlan() throws Exception {
        mvc.perform(get("/api/plans/{id}", UUID.randomUUID()).with(manager()))
                .andExpect(status().isNotFound())
                .andExpect(CONTRACT);
    }

    @Test
    void updatesAPlan() throws Exception {
        mvc.perform(put("/api/plans/{id}", planId).with(manager()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Pro Plus\"}"))
                .andExpect(status().isOk())
                .andExpect(CONTRACT);
    }

    @Test
    void deactivatesAPlanOnce() throws Exception {
        mvc.perform(delete("/api/plans/{id}", planId).with(manager()))
                .andExpect(status().isOk())
                .andExpect(CONTRACT);

        mvc.perform(delete("/api/plans/{id}", planId).with(manager()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(CONTRACT);
    }

    @Test
    void listsPlans() throws Exception {
        mvc.perform(get("/api/plans").with(manager())
                .param("q", "pro")
                .param("filter", "activeIntervals:MONTHLY")
                .param("sort", "createdAt:desc")
                .param("page", "0")
                .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(CONTRACT);
    }

    @Test
    void addsAPrice() throws Exception {
        mvc.perform(post("/api/plans/{id}/prices", planId).with(manager()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"price\":499.00,\"currency\":\"BRL\",\"interval\":\"YEARLY\"}"))
                .andExpect(status().isCreated())
                .andExpect(CONTRACT);
    }

    @Test
    void refusesASecondActivePriceOnOnePair() throws Exception {
        mvc.perform(post("/api/plans/{id}/prices", planId).with(manager()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"price\":59.90,\"currency\":\"BRL\",\"interval\":\"MONTHLY\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(CONTRACT);
    }

    @Test
    void replacesAPrice() throws Exception {
        mvc.perform(post("/api/prices/{id}/replace", priceId).with(manager()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"price\":59.90}"))
                .andExpect(status().isCreated())
                .andExpect(CONTRACT);
    }

    @Test
    void deactivatesAPrice() throws Exception {
        mvc.perform(delete("/api/prices/{id}", priceId).with(manager()))
                .andExpect(status().isOk())
                .andExpect(CONTRACT);
    }

    @Test
    void reportsAMissingPrice() throws Exception {
        mvc.perform(delete("/api/prices/{id}", UUID.randomUUID()).with(manager()))
                .andExpect(status().isNotFound())
                .andExpect(CONTRACT);
    }

    @Test
    void refusesAMissingPermission() throws Exception {
        mvc.perform(post("/api/plans").with(httpBasic("viewer@recurve.local", PASSWORD))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Basic\"}"))
                .andExpect(status().isForbidden())
                .andExpect(CONTRACT);
    }

    @Test
    void rebuildsTheIndex() throws Exception {
        mvc.perform(post("/api/plans/reindex").with(manager()))
                .andExpect(status().isOk())
                .andExpect(CONTRACT);
    }

    private static RequestPostProcessor manager() {
        return httpBasic("manager@recurve.local", PASSWORD);
    }
}
