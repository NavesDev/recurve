package com.navesdev.recurve.payment;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import com.navesdev.recurve.payment.domain.Payment;
import com.navesdev.recurve.payment.domain.PaymentSummary;
import com.navesdev.recurve.payment.repository.PaymentRepository;
import com.navesdev.recurve.payment.repository.PaymentSearchRepository;
import com.navesdev.recurve.plan.domain.BillingInterval;
import com.navesdev.recurve.plan.domain.Plan;
import com.navesdev.recurve.plan.domain.PlanPrice;
import com.navesdev.recurve.plan.repository.PlanRepository;
import com.navesdev.recurve.subscriber.domain.Subscriber;
import com.navesdev.recurve.subscriber.repository.SubscriberRepository;
import com.navesdev.recurve.subscriber.repository.SubscriberSearchRepository;
import com.navesdev.recurve.user.domain.Permission;
import com.navesdev.recurve.user.domain.User;
import com.navesdev.recurve.user.repository.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * The payment route rules in {@code SecurityConfig}, over real HTTP Basic,
 * the real service and the fake gateway: VIEW_PAYMENTS reads, MANAGE_PAYMENTS
 * acts and reads too (BR-01), rebuilding the index is a system operation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PaymentEndpointAuthorizationIT {

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
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentSearchRepository paymentSearchRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @PersistenceContext
    private EntityManager entityManager;

    private UUID subscriberId;
    private UUID paymentId;

    @BeforeEach
    void setUp() {
        paymentSearchRepository.recreateIndex();
        subscriberSearchRepository.recreateIndex();
        entityManager.createNativeQuery("TRUNCATE users, payments, subscribers, plans CASCADE").executeUpdate();

        register("viewer@recurve.local", Permission.VIEW_PAYMENTS);
        register("manager@recurve.local", Permission.MANAGE_PAYMENTS);
        register("outsider@recurve.local", Permission.MANAGE_SUBSCRIBERS);
        register("sysadmin@recurve.local", Permission.MANAGE_SYSTEM);

        Plan plan = Plan.create("Pro", null, NOW);
        PlanPrice monthly = plan.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW);
        planRepository.save(plan);
        Subscriber grace = subscriberRepository.save(Subscriber.start("Grace", "grace@navy.mil", "52998224725", monthly, NOW));
        Subscriber ada = subscriberRepository.save(Subscriber.start("Ada", "ada@engine.org", "11222333000181", monthly, NOW));
        subscriberId = ada.getId();
        Payment payment = paymentRepository.save(Payment.charge(grace, monthly, NOW));
        paymentId = paymentSearchRepository.save(PaymentSummary.of(payment)).id();
        entityManager.flush();
    }

    @Nested
    @DisplayName("VIEW_PAYMENTS reads and nothing else")
    class Viewer {

        @Test
        void listsAndReadsPayments() throws Exception {
            mvc.perform(get("/api/payments").with(as("viewer"))).andExpect(status().isOk());
            mvc.perform(get("/api/payments/{id}", paymentId).with(as("viewer"))).andExpect(status().isOk());
        }

        @Test
        void actsOnNothing() throws Exception {
            mvc.perform(post("/api/payments").with(as("viewer")).contentType(MediaType.APPLICATION_JSON)
                    .content(request())).andExpect(status().isForbidden());
            mvc.perform(post("/api/payments/{id}/send", paymentId).with(as("viewer"))).andExpect(status().isForbidden());
            mvc.perform(post("/api/payments/{id}/confirm", paymentId).with(as("viewer"))).andExpect(status().isForbidden());
            mvc.perform(post("/api/payments/{id}/refund", paymentId).with(as("viewer"))).andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("MANAGE_PAYMENTS acts, and reads because manage implies view (BR-01)")
    class Manager {

        @Test
        void requestsSendsConfirmsAndRefunds() throws Exception {
            mvc.perform(post("/api/payments").with(as("manager")).contentType(MediaType.APPLICATION_JSON)
                    .content(request()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.invoiceUrl").isNotEmpty());
            mvc.perform(post("/api/payments/{id}/send", paymentId).with(as("manager")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.externalId").isNotEmpty());
            mvc.perform(post("/api/payments/{id}/confirm", paymentId).with(as("manager")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PAID"));
            mvc.perform(post("/api/payments/{id}/refund", paymentId).with(as("manager")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("REFUNDED"));
        }

        @Test
        void aCycleAlreadyChargedIsABusinessRuleViolation() throws Exception {
            mvc.perform(post("/api/payments").with(as("manager")).contentType(MediaType.APPLICATION_JSON)
                    .content(request())).andExpect(status().isCreated());
            mvc.perform(post("/api/payments").with(as("manager")).contentType(MediaType.APPLICATION_JSON)
                    .content(request())).andExpect(status().isUnprocessableEntity());
        }

        @Test
        void listsPayments() throws Exception {
            mvc.perform(get("/api/payments").with(as("manager")).param("filter", "status:PENDING"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1));
        }
    }

    @Test
    void anOperatorWithNoPaymentPermissionIsRefusedEvenAListing() throws Exception {
        mvc.perform(get("/api/payments").with(as("outsider"))).andExpect(status().isForbidden());
    }

    @Nested
    @DisplayName("Rebuilding the payment index is a system operation")
    class Reindexing {

        @Test
        void managingPaymentsIsNotEnough() throws Exception {
            mvc.perform(post("/api/payments/reindex").with(as("manager"))).andExpect(status().isForbidden());
        }

        @Test
        void managingTheSystemRebuildsTheIndexFromTheDatabase() throws Exception {
            mvc.perform(post("/api/payments/reindex").with(as("sysadmin")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.indexed").value(1));
        }
    }

    @Test
    void withTheFakeGatewayThereIsNoWebhookAtAll() throws Exception {
        // The webhook only exists when Asaas is the gateway; anything else
        // under /api is an operator's route and needs one.
        mvc.perform(post("/api/webhooks/asaas").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    private String request() {
        return "{\"subscriberId\":\"%s\"}".formatted(subscriberId);
    }

    private static RequestPostProcessor as(String who) {
        return httpBasic(who + "@recurve.local", PASSWORD);
    }

    private void register(String email, Permission permission) {
        userRepository.save(User.create(email, email, passwordEncoder.encode(PASSWORD), Set.of(permission), NOW));
    }
}
