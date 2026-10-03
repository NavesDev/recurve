package com.navesdev.recurve.subscriber;

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
 * The subscriber route rules in {@code SecurityConfig}, over real HTTP
 * Basic and the real service: VIEW_SUBSCRIBERS reads, MANAGE_SUBSCRIBERS
 * writes and reads too (BR-01), rebuilding the index is a system
 * operation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SubscriberEndpointAuthorizationIT {

    private static final String PASSWORD = "s3cret-password";
    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

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

        register("viewer@recurve.local", Permission.VIEW_SUBSCRIBERS);
        register("manager@recurve.local", Permission.MANAGE_SUBSCRIBERS);
        register("outsider@recurve.local", Permission.MANAGE_PLANS);
        register("sysadmin@recurve.local", Permission.MANAGE_SYSTEM);

        Plan plan = Plan.create("Pro", null, NOW);
        PlanPrice price = plan.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW);
        planRepository.save(plan);
        priceId = price.getId();
        Subscriber grace = subscriberRepository.save(Subscriber.start("Grace", "grace@navy.mil", "52998224725", price, NOW));
        subscriberId = subscriberSearchRepository.save(SubscriberSummary.of(grace, plan)).id();
        entityManager.flush();
    }

    @Nested
    @DisplayName("VIEW_SUBSCRIBERS reads and nothing else")
    class Viewer {

        @Test
        void listsAndReadsSubscribers() throws Exception {
            mvc.perform(get("/api/subscribers").with(as("viewer"))).andExpect(status().isOk());
            mvc.perform(get("/api/subscribers/{id}", subscriberId).with(as("viewer"))).andExpect(status().isOk());
        }

        @Test
        void changesNothing() throws Exception {
            mvc.perform(post("/api/subscribers").with(as("viewer")).contentType(MediaType.APPLICATION_JSON)
                    .content(newSubscriber())).andExpect(status().isForbidden());
            mvc.perform(put("/api/subscribers/{id}", subscriberId).with(as("viewer")).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Grace\",\"email\":\"gbh@navy.mil\",\"document\":\"52998224725\"}")).andExpect(status().isForbidden());
            mvc.perform(delete("/api/subscribers/{id}", subscriberId).with(as("viewer"))).andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("MANAGE_SUBSCRIBERS writes, and reads because manage implies view (BR-01)")
    class Manager {

        @Test
        void listsSubscribers() throws Exception {
            mvc.perform(get("/api/subscribers").with(as("manager")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1));
        }

        @Test
        void registersEditsAndCancels() throws Exception {
            mvc.perform(post("/api/subscribers").with(as("manager")).contentType(MediaType.APPLICATION_JSON)
                    .content(newSubscriber()))
                    .andExpect(status().isCreated());
            mvc.perform(put("/api/subscribers/{id}", subscriberId).with(as("manager")).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Grace B. Hopper\",\"email\":\"gbh@navy.mil\",\"document\":\"52998224725\"}"))
                    .andExpect(status().isOk());
            mvc.perform(delete("/api/subscribers/{id}", subscriberId).with(as("manager")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELED"));
        }

        @Test
        void anEmailInUseIsABusinessRuleViolation() throws Exception {
            mvc.perform(post("/api/subscribers").with(as("manager")).contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"name":"Grace","email":"GRACE@navy.mil","document":"52998224725","planPriceId":"%s"}
                            """.formatted(priceId)))
                    .andExpect(status().isUnprocessableEntity());
        }

        @Test
        void filtersByPlanEndToEnd() throws Exception {
            mvc.perform(get("/api/subscribers").with(as("manager")).param("filter", "planId:" + UUID.randomUUID()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0));
        }
    }

    @Test
    void anOperatorWithNoSubscriberPermissionIsRefusedEvenAListing() throws Exception {
        mvc.perform(get("/api/subscribers").with(as("outsider"))).andExpect(status().isForbidden());
    }

    @Nested
    @DisplayName("Rebuilding the subscriber index is a system operation")
    class Reindexing {

        @Test
        void managingSubscribersIsNotEnough() throws Exception {
            mvc.perform(post("/api/subscribers/reindex").with(as("manager"))).andExpect(status().isForbidden());
        }

        @Test
        void managingTheSystemRebuildsTheIndexFromTheDatabase() throws Exception {
            mvc.perform(post("/api/subscribers/reindex").with(as("sysadmin")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.indexed").value(1));
        }
    }

    private String newSubscriber() {
        return """
                {"name":"Ada","email":"ada@engine.org","document":"52998224725","planPriceId":"%s"}
                """.formatted(priceId);
    }

    private static RequestPostProcessor as(String who) {
        return httpBasic(who + "@recurve.local", PASSWORD);
    }

    private void register(String email, Permission permission) {
        userRepository.save(User.create(email, email, passwordEncoder.encode(PASSWORD), Set.of(permission), NOW));
    }
}
