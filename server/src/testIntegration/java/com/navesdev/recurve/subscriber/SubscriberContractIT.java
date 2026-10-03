package com.navesdev.recurve.subscriber;

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
import com.navesdev.recurve.plan.domain.PlanPrice;
import com.navesdev.recurve.plan.repository.PlanRepository;
import com.navesdev.recurve.subscriber.domain.Subscriber;
import com.navesdev.recurve.subscriber.domain.SubscriberSummary;
import com.navesdev.recurve.subscriber.repository.SubscriberRepository;
import com.navesdev.recurve.subscriber.repository.SubscriberSearchRepository;
import com.navesdev.recurve.user.domain.Permission;
import com.navesdev.recurve.user.domain.User;
import com.navesdev.recurve.user.repository.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Every real subscriber exchange, request and response, must be one
 * {@code docs/openapi.yaml} allows. Behaviour is covered elsewhere; this
 * asks only whether what went over the wire matches what was promised.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SubscriberContractIT {

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
    private SubscriberRepository subscriberRepository;

    @Autowired
    private SubscriberSearchRepository subscriberSearchRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @PersistenceContext
    private EntityManager entityManager;

    private UUID subscriberId;
    private UUID priceId;

    @BeforeEach
    void setUp() {
        subscriberSearchRepository.recreateIndex();
        entityManager.createNativeQuery("TRUNCATE users, subscribers, plans CASCADE").executeUpdate();

        userRepository.save(User.create("Maya Manager", "manager@recurve.local", passwordEncoder.encode(PASSWORD),
                Set.of(Permission.MANAGE_SUBSCRIBERS, Permission.MANAGE_SYSTEM), NOW));
        userRepository.save(User.create("Vera Viewer", "viewer@recurve.local", passwordEncoder.encode(PASSWORD),
                Set.of(Permission.VIEW_SUBSCRIBERS), NOW));

        Plan plan = Plan.create("Pro", null, NOW);
        PlanPrice price = plan.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW);
        planRepository.save(plan);
        priceId = price.getId();
        Subscriber grace = subscriberRepository.save(Subscriber.start("Grace Hopper", "grace@navy.mil", price, NOW));
        subscriberId = subscriberSearchRepository.save(SubscriberSummary.of(grace, plan)).id();
        entityManager.flush();
    }

    @Test
    void registersASubscriber() throws Exception {
        mvc.perform(post("/api/subscribers").with(manager()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Ada\",\"email\":\"ada@engine.org\",\"planPriceId\":\"%s\"}".formatted(priceId)))
                .andExpect(status().isCreated())
                .andExpect(CONTRACT);
    }

    @Test
    void refusesAnInvalidBody() throws Exception {
        mvc.perform(post("/api/subscribers").with(manager()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(CONTRACT_RESPONSE);
    }

    @Test
    void refusesAnEmailInUse() throws Exception {
        mvc.perform(post("/api/subscribers").with(manager()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Grace\",\"email\":\"grace@navy.mil\",\"planPriceId\":\"%s\"}".formatted(priceId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(CONTRACT);
    }

    @Test
    void reportsAMissingPrice() throws Exception {
        mvc.perform(post("/api/subscribers").with(manager()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Ada\",\"email\":\"ada@engine.org\",\"planPriceId\":\"%s\"}"
                        .formatted(UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(CONTRACT);
    }

    @Test
    void findsASubscriber() throws Exception {
        mvc.perform(get("/api/subscribers/{id}", subscriberId).with(manager()))
                .andExpect(status().isOk())
                .andExpect(CONTRACT);
    }

    @Test
    void reportsAMissingSubscriber() throws Exception {
        mvc.perform(get("/api/subscribers/{id}", UUID.randomUUID()).with(manager()))
                .andExpect(status().isNotFound())
                .andExpect(CONTRACT);
    }

    @Test
    void updatesASubscriber() throws Exception {
        mvc.perform(put("/api/subscribers/{id}", subscriberId).with(manager()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Grace B. Hopper\",\"email\":\"gbh@navy.mil\"}"))
                .andExpect(status().isOk())
                .andExpect(CONTRACT);
    }

    @Test
    void cancelsASubscriberOnce() throws Exception {
        mvc.perform(delete("/api/subscribers/{id}", subscriberId).with(manager()))
                .andExpect(status().isOk())
                .andExpect(CONTRACT);

        mvc.perform(delete("/api/subscribers/{id}", subscriberId).with(manager()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(CONTRACT);
    }

    @Test
    void listsSubscribers() throws Exception {
        mvc.perform(get("/api/subscribers").with(manager())
                .param("q", "grace")
                .param("filter", "status:ACTIVE,PAST_DUE")
                .param("sort", "price:desc")
                .param("page", "0")
                .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(CONTRACT);
    }

    @Test
    void refusesAMissingPermission() throws Exception {
        mvc.perform(delete("/api/subscribers/{id}", subscriberId).with(httpBasic("viewer@recurve.local", PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(CONTRACT);
    }

    @Test
    void rebuildsTheIndex() throws Exception {
        mvc.perform(post("/api/subscribers/reindex").with(manager()))
                .andExpect(status().isOk())
                .andExpect(CONTRACT);
    }

    private static RequestPostProcessor manager() {
        return httpBasic("manager@recurve.local", PASSWORD);
    }
}
