package com.navesdev.recurve.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.navesdev.recurve.payment.domain.Payment;
import com.navesdev.recurve.payment.domain.PaymentStatus;
import com.navesdev.recurve.payment.repository.PaymentRepository;
import com.navesdev.recurve.payment.repository.PaymentSearchRepository;
import com.navesdev.recurve.plan.domain.BillingInterval;
import com.navesdev.recurve.plan.domain.Plan;
import com.navesdev.recurve.plan.domain.PlanPrice;
import com.navesdev.recurve.plan.repository.PlanRepository;
import com.navesdev.recurve.subscriber.domain.Subscriber;
import com.navesdev.recurve.subscriber.repository.SubscriberRepository;
import com.navesdev.recurve.subscriber.repository.SubscriberSearchRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * The webhook with Asaas configured, through the real security chain: no
 * operator is needed, Asaas's token is, and an event moves the payment.
 * Asaas itself is never called — the webhook does not call it.
 */
@SpringBootTest(properties = {
        "recurve.payment.gateway=asaas",
        "recurve.payment.asaas.api-url=https://api-sandbox.asaas.com/v3",
        "recurve.payment.asaas.api-key=$aact_never_used_by_this_test",
        "recurve.payment.asaas.webhook-token=" + AsaasWebhookIT.TOKEN })
@AutoConfigureMockMvc
@Transactional
class AsaasWebhookIT {

    static final String TOKEN = "whsec_a-token-of-at-least-thirty-two-chars";
    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    @Autowired
    private MockMvc mvc;

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

    @PersistenceContext
    private EntityManager entityManager;

    private Payment payment;

    @BeforeEach
    void setUp() {
        paymentSearchRepository.recreateIndex();
        subscriberSearchRepository.recreateIndex();
        entityManager.createNativeQuery("TRUNCATE payments, subscribers, plans CASCADE").executeUpdate();
        Plan plan = Plan.create("Pro", null, NOW);
        PlanPrice price = plan.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW);
        planRepository.save(plan);
        Subscriber grace = subscriberRepository.save(Subscriber.start("Grace", "grace@navy.mil", "52998224725", price, NOW));
        payment = Payment.charge(grace, price, NOW);
        payment.registerAtGateway("pay_080225913252", "https://sandbox.asaas.com/i/080225913252");
        payment = paymentRepository.save(payment);
        entityManager.flush();
    }

    @Test
    void asaasNeedsNoOperatorButItsToken() throws Exception {
        mvc.perform(webhook().header("asaas-access-token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid("/docs/openapi.yaml"));

        entityManager.flush();
        entityManager.clear();
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    void withoutTheTokenNothingHappensEvenForAnOperator() throws Exception {
        mvc.perform(webhook().with(httpBasic("anyone@recurve.local", "whatever"))).andExpect(status().isUnauthorized());
        mvc.perform(webhook()).andExpect(status().isUnauthorized());

        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    private MockHttpServletRequestBuilder webhook() {
        return post("/api/webhooks/asaas").contentType(MediaType.APPLICATION_JSON).content("""
                {"id":"evt_1","event":"PAYMENT_RECEIVED","dateCreated":"2026-01-15 10:00:00",
                 "payment":{"object":"payment","id":"pay_080225913252","externalReference":"%s"}}
                """.formatted(payment.getId()));
    }
}
