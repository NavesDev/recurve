package com.navesdev.recurve.payment;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * Every real payment exchange, request and response, must be one
 * {@code docs/openapi.yaml} allows. Runs on the fake gateway.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PaymentContractIT {

    private static final String PASSWORD = "s3cret-password";
    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    private static final String SPEC = "/docs/openapi.yaml";

    private static final ResultMatcher CONTRACT = openApi()
            .isValid(OpenApiInteractionValidator.createForSpecificationUrl(SPEC).build());

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

        userRepository.save(User.create("Maya Manager", "manager@recurve.local", passwordEncoder.encode(PASSWORD),
                Set.of(Permission.MANAGE_PAYMENTS, Permission.MANAGE_SYSTEM), NOW));
        userRepository.save(User.create("Vera Viewer", "viewer@recurve.local", passwordEncoder.encode(PASSWORD),
                Set.of(Permission.VIEW_PAYMENTS), NOW));

        Plan plan = Plan.create("Pro", null, NOW);
        PlanPrice price = plan.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW);
        planRepository.save(plan);
        Subscriber grace = subscriberRepository.save(Subscriber.start("Grace", "grace@navy.mil", "52998224725", price, NOW));
        subscriberId = subscriberRepository.save(Subscriber.start("Ada", "ada@engine.org", "11222333000181", price, NOW)).getId();
        Payment payment = Payment.charge(grace, price, NOW);
        payment.registerAtGateway("fake_pay_1", "https://gateway.invalid/pay/1");
        paymentId = paymentSearchRepository.save(PaymentSummary.of(paymentRepository.save(payment))).id();
        entityManager.flush();
    }

    @Test
    void requestsACharge() throws Exception {
        mvc.perform(post("/api/payments").with(manager()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"subscriberId\":\"%s\"}".formatted(subscriberId)))
                .andExpect(status().isCreated())
                .andExpect(CONTRACT);
    }

    @Test
    void refusesAnInvalidBody() throws Exception {
        mvc.perform(post("/api/payments").with(manager()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(CONTRACT_RESPONSE);
    }

    @Test
    void reportsAMissingSubscriber() throws Exception {
        mvc.perform(post("/api/payments").with(manager()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"subscriberId\":\"%s\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(CONTRACT);
    }

    @Test
    void findsAPayment() throws Exception {
        mvc.perform(get("/api/payments/{id}", paymentId).with(manager())).andExpect(status().isOk()).andExpect(CONTRACT);
    }

    @Test
    void sendsConfirmsAndRefunds() throws Exception {
        mvc.perform(post("/api/payments/{id}/send", paymentId).with(manager())).andExpect(status().isOk()).andExpect(CONTRACT);
        mvc.perform(post("/api/payments/{id}/sync", paymentId).with(manager())).andExpect(status().isOk()).andExpect(CONTRACT);
        mvc.perform(post("/api/payments/{id}/confirm", paymentId).with(manager())).andExpect(status().isOk()).andExpect(CONTRACT);
        mvc.perform(post("/api/payments/{id}/confirm", paymentId).with(manager()))
                .andExpect(status().isUnprocessableEntity()).andExpect(CONTRACT);
        mvc.perform(post("/api/payments/{id}/refund", paymentId).with(manager())).andExpect(status().isOk()).andExpect(CONTRACT);
    }

    @Test
    void listsPayments() throws Exception {
        mvc.perform(get("/api/payments").with(manager())
                .param("filter", "status:PENDING,PAID")
                .param("sort", "amount:desc")
                .param("page", "0")
                .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(CONTRACT);
    }

    @Test
    void refusesAMissingPermission() throws Exception {
        mvc.perform(post("/api/payments/{id}/refund", paymentId).with(httpBasic("viewer@recurve.local", PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(CONTRACT);
    }

    @Test
    void rebuildsTheIndex() throws Exception {
        mvc.perform(post("/api/payments/reindex").with(manager())).andExpect(status().isOk()).andExpect(CONTRACT);
    }

    private static RequestPostProcessor manager() {
        return httpBasic("manager@recurve.local", PASSWORD);
    }
}
