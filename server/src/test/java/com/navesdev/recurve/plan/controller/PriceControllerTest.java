package com.navesdev.recurve.plan.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.navesdev.recurve.plan.domain.BillingInterval;
import com.navesdev.recurve.plan.domain.Plan;
import com.navesdev.recurve.plan.domain.PlanPrice;
import com.navesdev.recurve.plan.domain.exception.PriceAlreadyInactiveException;
import com.navesdev.recurve.plan.domain.exception.PriceNotFoundException;
import com.navesdev.recurve.plan.service.PlanService;
import com.navesdev.recurve.plan.service.ReplacePriceCommand;
import com.navesdev.recurve.shared.controller.GlobalExceptionHandler;
import com.navesdev.recurve.shared.controller.ListingRequests;

/**
 * A price that exists is addressed by its own id alone. ListingRequests is
 * imported only because the slice builds the shared listing resolver.
 */
@WebMvcTest(PriceController.class)
@Import({ GlobalExceptionHandler.class, ListingRequests.class, PriceControllerTest.FixedClock.class })
class PriceControllerTest {

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
    private PlanService service;

    @Nested
    @DisplayName("FR-02.7 / BR-04 replacing a price")
    class Replacing {

        @Test
        void theReplacementIsCreatedAndThePlanComesBackWithBothPrices() throws Exception {
            Plan plan = Plan.create("Pro", null, NOW);
            PlanPrice old = plan.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW);
            plan.replacePrice(old.getId(), new BigDecimal("59.90"), NOW);
            ArgumentCaptor<ReplacePriceCommand> sent = ArgumentCaptor.forClass(ReplacePriceCommand.class);
            when(service.replacePrice(sent.capture())).thenReturn(plan);

            mvc.perform(post("/api/prices/{id}/replace", old.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"price":59.90}
                            """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.prices.length()").value(2))
                    .andExpect(jsonPath("$.prices[0].active").value(false))
                    .andExpect(jsonPath("$.prices[1].active").value(true));

            assertThat(sent.getValue().priceId()).isEqualTo(old.getId());
            assertThat(sent.getValue().price()).isEqualByComparingTo("59.90");
        }

        @Test
        void aReplacementWithoutAnAmountIsRefused() throws Exception {
            mvc.perform(post("/api/prices/{id}/replace", UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[*].field").value("price"));

            verify(service, never()).replacePrice(any());
        }

        @Test
        void aMissingPriceIsNotFound() throws Exception {
            UUID id = UUID.randomUUID();
            when(service.replacePrice(any())).thenThrow(new PriceNotFoundException(id));

            mvc.perform(post("/api/prices/{id}/replace", id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"price":59.90}
                            """))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("FR-02.3 deactivating a price")
    class Deactivating {

        @Test
        void aDeactivatedPriceComesBackInactiveInItsPlan() throws Exception {
            Plan plan = Plan.create("Pro", null, NOW);
            PlanPrice price = plan.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW);
            plan.deactivatePrice(price.getId());
            when(service.deactivatePrice(price.getId())).thenReturn(plan);

            mvc.perform(delete("/api/prices/{id}", price.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.prices[0].active").value(false));
        }

        @Test
        void aPriceIsDeactivatedOnlyOnce() throws Exception {
            UUID id = UUID.randomUUID();
            when(service.deactivatePrice(id)).thenThrow(new PriceAlreadyInactiveException(id));

            mvc.perform(delete("/api/prices/{id}", id))
                    .andExpect(status().isUnprocessableEntity());
        }
    }
}
