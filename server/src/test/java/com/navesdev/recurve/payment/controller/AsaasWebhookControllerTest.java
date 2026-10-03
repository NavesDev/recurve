package com.navesdev.recurve.payment.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.navesdev.recurve.payment.gateway.AsaasProperties;
import com.navesdev.recurve.payment.service.GatewayEvent;
import com.navesdev.recurve.payment.service.PaymentService;
import com.navesdev.recurve.shared.controller.GlobalExceptionHandler;
import com.navesdev.recurve.shared.controller.ListingRequests;

/**
 * The Asaas webhook: it answers to Asaas's token and nothing else, and
 * hands the service the event stripped of Asaas's envelope.
 */
@WebMvcTest(controllers = AsaasWebhookController.class, properties = "recurve.payment.gateway=asaas")
@Import({ GlobalExceptionHandler.class, ListingRequests.class, AsaasWebhookControllerTest.Config.class })
class AsaasWebhookControllerTest {

    private static final String TOKEN = "whsec_a-token-of-at-least-thirty-two-chars";

    @TestConfiguration
    static class Config {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-01-15T10:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        AsaasProperties asaasProperties() {
            return new AsaasProperties("https://api-sandbox.asaas.com/v3", "$aact_key", TOKEN);
        }
    }

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PaymentService service;

    @Test
    void anEventWithTheTokenReachesTheServiceAsAGatewayEvent() throws Exception {
        ArgumentCaptor<GatewayEvent> event = ArgumentCaptor.forClass(GatewayEvent.class);

        mvc.perform(webhook().header("asaas-access-token", TOKEN))
                .andExpect(status().isOk());

        verify(service).handle(event.capture());
        assertThat(event.getValue()).isEqualTo(
                new GatewayEvent("PAYMENT_RECEIVED", "5e4d3c2b-1a09-4f8e-9d7c-6b5a4f3e2d1c", "pay_080225913252"));
    }

    @Test
    void anEventWithoutTheTokenIsRefused() throws Exception {
        mvc.perform(webhook())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        verify(service, never()).handle(any());
    }

    @Test
    void anEventWithAnotherTokenIsRefused() throws Exception {
        mvc.perform(webhook().header("asaas-access-token", TOKEN + "x")).andExpect(status().isUnauthorized());

        verify(service, never()).handle(any());
    }

    private static MockHttpServletRequestBuilder webhook() {
        return post("/api/webhooks/asaas").contentType(MediaType.APPLICATION_JSON).content("""
                {"id":"evt_05b708f961d739ea7eba7e4db318f621&368604920","event":"PAYMENT_RECEIVED",
                 "dateCreated":"2026-01-15 10:00:00",
                 "account":{"id":"acc_1","ownerId":null},
                 "payment":{"object":"payment","id":"pay_080225913252","customer":"cus_000005219613",
                            "value":49.90,"status":"RECEIVED",
                            "externalReference":"5e4d3c2b-1a09-4f8e-9d7c-6b5a4f3e2d1c"}}
                """);
    }
}
