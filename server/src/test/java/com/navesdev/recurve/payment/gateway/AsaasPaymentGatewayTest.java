package com.navesdev.recurve.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.navesdev.recurve.payment.domain.Payment;
import com.navesdev.recurve.payment.domain.exception.PaymentGatewayException;
import com.navesdev.recurve.plan.domain.BillingInterval;
import com.navesdev.recurve.plan.domain.Plan;
import com.navesdev.recurve.plan.domain.PlanPrice;
import com.navesdev.recurve.subscriber.domain.Subscriber;

/**
 * What goes over the wire to Asaas, against a mock server: the calls the
 * documentation describes, the key in its header, and every failure
 * turned into the one exception the service knows.
 */
class AsaasPaymentGatewayTest {

    private static final String API = "https://api-sandbox.asaas.com/v3";
    private static final String KEY = "$aact_sandbox_key_for_tests";
    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    private MockRestServiceServer server;
    private AsaasPaymentGateway gateway;

    private Subscriber subscriber;
    private Payment payment;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new AsaasPaymentGateway(builder, new AsaasProperties(API, KEY, "webhook-token-of-at-least-32-characters"));

        PlanPrice price = Plan.create("Pro", null, NOW).addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW);
        subscriber = Subscriber.start("Grace Hopper", "grace@navy.mil", "52998224725", price, NOW);
        payment = Payment.charge(subscriber, price, NOW);
    }

    @Nested
    @DisplayName("A subscriber is one customer at Asaas")
    class Customers {

        @Test
        void aSubscriberWithoutACustomerBecomesOne() {
            server.expect(requestTo(API + "/customers"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header("access_token", KEY))
                    .andExpect(content().json("""
                            {"name":"Grace Hopper","cpfCnpj":"52998224725","email":"grace@navy.mil",
                             "externalReference":"%s"}
                            """.formatted(subscriber.getId())))
                    .andRespond(withSuccess("""
                            {"object":"customer","id":"cus_000005219613","name":"Grace Hopper"}
                            """, MediaType.APPLICATION_JSON));

            assertThat(gateway.ensureCustomer(subscriber)).isEqualTo("cus_000005219613");
            server.verify();
        }

        @Test
        void aSubscriberThatIsAlreadyACustomerIsNotCreatedAgain() {
            subscriber.attachGatewayCustomer("cus_000005219613");

            assertThat(gateway.ensureCustomer(subscriber)).isEqualTo("cus_000005219613");
            server.verify();
        }
    }

    @Nested
    @DisplayName("FR-04.7 a charge at Asaas")
    class Charges {

        @Test
        void aChargeLetsTheCustomerChooseHowToPayAndCarriesOurId() {
            server.expect(requestTo(API + "/payments"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header("access_token", KEY))
                    .andExpect(content().json("""
                            {"customer":"cus_000005219613","billingType":"UNDEFINED","value":49.90,
                             "dueDate":"2026-02-15","externalReference":"%s"}
                            """.formatted(payment.getId())))
                    .andRespond(withSuccess("""
                            {"object":"payment","id":"pay_080225913252","status":"PENDING",
                             "invoiceUrl":"https://sandbox.asaas.com/i/080225913252"}
                            """, MediaType.APPLICATION_JSON));

            PaymentGateway.Charge charge = gateway.createCharge(payment, "cus_000005219613");

            assertThat(charge.externalId()).isEqualTo("pay_080225913252");
            assertThat(charge.invoiceUrl()).isEqualTo("https://sandbox.asaas.com/i/080225913252");
            server.verify();
        }

        @Test
        void aChargeThatReachedAsaasIsFoundByOurId() {
            server.expect(requestTo(API + "/payments?externalReference=" + payment.getId()))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess("""
                            {"object":"list","totalCount":1,
                             "data":[{"id":"pay_080225913252","invoiceUrl":"https://sandbox.asaas.com/i/080225913252"}]}
                            """, MediaType.APPLICATION_JSON));

            assertThat(gateway.findCharge(payment.getId()))
                    .contains(new PaymentGateway.Charge("pay_080225913252", "https://sandbox.asaas.com/i/080225913252"));
        }

        @Test
        void aChargeThatNeverReachedAsaasIsNotFound() {
            server.expect(requestTo(API + "/payments?externalReference=" + payment.getId()))
                    .andRespond(withSuccess("""
                            {"object":"list","totalCount":0,"data":[]}
                            """, MediaType.APPLICATION_JSON));

            assertThat(gateway.findCharge(payment.getId())).isEmpty();
        }

        @Test
        void aPaymentReceivedOutsideAsaasIsReportedToIt() {
            server.expect(requestTo(API + "/payments/pay_080225913252/receiveInCash"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(content().json("""
                            {"paymentDate":"2026-02-20","value":49.90}
                            """))
                    .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

            gateway.receiveInCash("pay_080225913252", new BigDecimal("49.90"), LocalDate.parse("2026-02-20"));
            server.verify();
        }

        @Test
        void aRefundIsAskedOfAsaas() {
            server.expect(requestTo(API + "/payments/pay_080225913252/refund"))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

            gateway.refund("pay_080225913252");
            server.verify();
        }
    }

    @Nested
    @DisplayName("A failure at Asaas is the service's one gateway exception")
    class Failures {

        @Test
        void aRefusedCallNamesTheOperationAndTheStatusButNotTheKeyOrBody() {
            server.expect(requestTo(API + "/customers"))
                    .andRespond(withBadRequest().body("""
                            {"errors":[{"code":"invalid_cpfCnpj","description":"secret detail"}]}
                            """).contentType(MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> gateway.ensureCustomer(subscriber))
                    .isInstanceOf(PaymentGatewayException.class)
                    .hasMessageContaining("customer")
                    .hasMessageContaining("400")
                    .hasMessageNotContaining(KEY)
                    .hasMessageNotContaining("secret detail");
        }

        @Test
        void aServerErrorIsAGatewayFailure() {
            server.expect(requestTo(API + "/payments/pay_1/refund")).andRespond(withServerError());

            assertThatThrownBy(() -> gateway.refund("pay_1")).isInstanceOf(PaymentGatewayException.class);
        }
    }

    @Test
    void aGatewayWithoutAKeyOrWebhookTokenDoesNotStart() {
        // Fail-fast: an Asaas gateway configured without credentials would
        // fail on every charge instead of at startup.
        assertThatThrownBy(() -> new AsaasProperties(API, " ", "webhook-token-of-at-least-32-characters"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("api-key");
        assertThatThrownBy(() -> new AsaasProperties(API, KEY, ""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("webhook-token");
    }
}
