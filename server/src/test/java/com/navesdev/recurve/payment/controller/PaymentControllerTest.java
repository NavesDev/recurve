package com.navesdev.recurve.payment.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.navesdev.recurve.payment.domain.Payment;
import com.navesdev.recurve.payment.domain.PaymentSummary;
import com.navesdev.recurve.payment.domain.exception.PaymentGatewayException;
import com.navesdev.recurve.payment.domain.exception.PaymentNotFoundException;
import com.navesdev.recurve.payment.domain.exception.PaymentNotRefundableException;
import com.navesdev.recurve.payment.domain.PaymentStatus;
import com.navesdev.recurve.payment.service.PaymentService;
import com.navesdev.recurve.payment.service.RequestPaymentCommand;
import com.navesdev.recurve.plan.domain.BillingInterval;
import com.navesdev.recurve.plan.domain.Plan;
import com.navesdev.recurve.plan.domain.PlanPrice;
import com.navesdev.recurve.shared.controller.GlobalExceptionHandler;
import com.navesdev.recurve.shared.controller.ListingRequests;
import com.navesdev.recurve.shared.service.SearchFilter;
import com.navesdev.recurve.subscriber.domain.Subscriber;

/**
 * What the payment API promises a client. Authorization needs the real
 * chain, so it lives in {@code PaymentEndpointAuthorizationIT}.
 */
@WebMvcTest(PaymentController.class)
@Import({ GlobalExceptionHandler.class, ListingRequests.class, PaymentControllerTest.FixedClock.class })
class PaymentControllerTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PaymentService service;

    @Nested
    @DisplayName("FR-04.1 requesting a charge")
    class Requesting {

        @Test
        void aRequestedChargeIsCreatedAtItsOwnAddressWithWhereToPay() throws Exception {
            PaymentSummary sent = sent();
            ArgumentCaptor<RequestPaymentCommand> command = ArgumentCaptor.forClass(RequestPaymentCommand.class);
            when(service.request(command.capture())).thenReturn(sent);

            mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"subscriberId\":\"%s\"}".formatted(sent.subscriberId())))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", "/api/payments/" + sent.id()))
                    .andExpect(jsonPath("$.status").value("PENDING"))
                    .andExpect(jsonPath("$.invoiceUrl").value("https://sandbox.asaas.com/i/1"))
                    .andExpect(content().string(containsString("\"amount\":49.90")));

            assertThat(command.getValue().subscriberId()).isEqualTo(sent.subscriberId());
        }

        @Test
        void aRequestWithoutASubscriberIsRefused() throws Exception {
            mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[*].field").value("subscriberId"));

            verify(service, never()).request(any());
        }

        @Test
        void aGatewayFailureIsABadGateway() throws Exception {
            when(service.request(any())).thenThrow(new PaymentGatewayException("Asaas could not be reached to create charge", null));

            mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"subscriberId\":\"%s\"}".formatted(UUID.randomUUID())))
                    .andExpect(status().isBadGateway());
        }
    }

    @Nested
    @DisplayName("Acting on a charge")
    class Acting {

        @Test
        void sendConfirmRefundAndSyncAnswerWithThePayment() throws Exception {
            UUID id = UUID.randomUUID();
            when(service.send(id)).thenReturn(sent());
            when(service.confirm(id)).thenReturn(sent());
            when(service.refund(id)).thenReturn(sent());
            when(service.sync(id)).thenReturn(sent());

            mvc.perform(post("/api/payments/{id}/send", id)).andExpect(status().isOk());
            mvc.perform(post("/api/payments/{id}/confirm", id)).andExpect(status().isOk());
            mvc.perform(post("/api/payments/{id}/refund", id)).andExpect(status().isOk());
            mvc.perform(post("/api/payments/{id}/sync", id)).andExpect(status().isOk());
        }

        @Test
        void refundingWhatWasNotPaidIsABusinessRuleViolation() throws Exception {
            UUID id = UUID.randomUUID();
            when(service.refund(id)).thenThrow(new PaymentNotRefundableException(id, PaymentStatus.PENDING));

            mvc.perform(post("/api/payments/{id}/refund", id)).andExpect(status().isUnprocessableEntity());
        }

        @Test
        void aChargeChangedByTheGatewayAtTheSameTimeIsAConflict() throws Exception {
            UUID id = UUID.randomUUID();
            when(service.confirm(id)).thenThrow(new ObjectOptimisticLockingFailureException(Payment.class, id));

            mvc.perform(post("/api/payments/{id}/confirm", id)).andExpect(status().isConflict());
        }

        @Test
        void aMissingPaymentIsNotFound() throws Exception {
            UUID id = UUID.randomUUID();
            when(service.findById(id)).thenThrow(new PaymentNotFoundException(id));

            mvc.perform(get("/api/payments/{id}", id)).andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("FR-04.6 / FR-07 the listing")
    class Listing {

        @Test
        void anAbsentSortIsLatestDueFirst() throws Exception {
            ArgumentCaptor<Pageable> sent = ArgumentCaptor.forClass(Pageable.class);
            when(service.search(any(SearchFilter.class), sent.capture()))
                    .thenReturn(new PageImpl<>(List.of(sent()), PageRequest.of(0, 20), 1));

            mvc.perform(get("/api/payments").param("filter", "status:PAID,FAILED"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].currency").value("BRL"));

            assertThat(sent.getValue().getSort().getOrderFor("dueAt")).isEqualTo(Sort.Order.desc("dueAt"));
        }
    }

    @Test
    void theRebuildReportsHowManyPaymentsTheIndexHolds() throws Exception {
        when(service.reindex()).thenReturn(4L);

        mvc.perform(post("/api/payments/reindex")).andExpect(status().isOk()).andExpect(jsonPath("$.indexed").value(4));
    }

    private static PaymentSummary sent() {
        PlanPrice price = Plan.create("Pro", null, NOW).addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW);
        Payment payment = Payment.charge(Subscriber.start("Grace", "grace@navy.mil", "52998224725", price, NOW), price, NOW);
        payment.registerAtGateway("pay_1", "https://sandbox.asaas.com/i/1");
        return PaymentSummary.of(payment);
    }
}
