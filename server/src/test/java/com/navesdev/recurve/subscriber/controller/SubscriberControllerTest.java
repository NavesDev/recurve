package com.navesdev.recurve.subscriber.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.navesdev.recurve.plan.domain.BillingInterval;
import com.navesdev.recurve.plan.domain.Plan;
import com.navesdev.recurve.plan.domain.PlanPrice;
import com.navesdev.recurve.plan.domain.exception.PriceInactiveException;
import com.navesdev.recurve.shared.controller.GlobalExceptionHandler;
import com.navesdev.recurve.shared.controller.ListingRequests;
import com.navesdev.recurve.shared.service.SearchFilter;
import com.navesdev.recurve.subscriber.domain.Subscriber;
import com.navesdev.recurve.subscriber.domain.SubscriberSummary;
import com.navesdev.recurve.subscriber.domain.exception.SubscriberNotFoundException;
import com.navesdev.recurve.subscriber.service.CreateSubscriberCommand;
import com.navesdev.recurve.subscriber.service.SubscriberService;
import com.navesdev.recurve.subscriber.service.UpdateSubscriberCommand;

/**
 * What the subscriber API promises a client: what counts as a bad
 * request, how an amount travels, and the shape of a page. Authorization
 * needs the real chain, so it lives in
 * {@code SubscriberEndpointAuthorizationIT}.
 */
@WebMvcTest(SubscriberController.class)
@Import({ GlobalExceptionHandler.class, ListingRequests.class, SubscriberControllerTest.FixedClock.class })
class SubscriberControllerTest {

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
    private SubscriberService service;

    @Nested
    @DisplayName("FR-03.1 registering a subscriber")
    class Registering {

        @Test
        void aRegisteredSubscriberIsCreatedAtItsOwnAddressWithWhatItPays() throws Exception {
            SubscriberSummary grace = grace();
            ArgumentCaptor<CreateSubscriberCommand> sent = ArgumentCaptor.forClass(CreateSubscriberCommand.class);
            when(service.create(sent.capture())).thenReturn(grace);

            mvc.perform(json(post("/api/subscribers"), """
                    {"name":"Grace","email":"grace@navy.mil","planPriceId":"%s"}
                    """.formatted(grace.planPriceId())))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", "/api/subscribers/" + grace.id()))
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.planId").value(grace.planId().toString()))
                    .andExpect(jsonPath("$.interval").value("MONTHLY"))
                    .andExpect(jsonPath("$.canceledAt").doesNotExist())
                    .andExpect(content().string(containsString("\"price\":49.90")));

            assertThat(sent.getValue().planPriceId()).isEqualTo(grace.planPriceId());
        }

        @Test
        void everyMissingFieldIsReportedAtOnce() throws Exception {
            mvc.perform(json(post("/api/subscribers"), """
                    {"name":"","email":"not-an-email"}
                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[*].field").value(containsInAnyOrder("name", "email", "planPriceId")));

            verify(service, never()).create(any());
        }

        @Test
        void aPriceNotOnSaleIsABusinessRuleViolation() throws Exception {
            UUID priceId = UUID.randomUUID();
            when(service.create(any())).thenThrow(new PriceInactiveException(priceId));

            mvc.perform(json(post("/api/subscribers"), """
                    {"name":"Grace","email":"grace@navy.mil","planPriceId":"%s"}
                    """.formatted(priceId)))
                    .andExpect(status().isUnprocessableEntity());
        }

        @Test
        void anEmailTakenByARaceIsAConflict() throws Exception {
            // BR-02: two requests passed the check at once; the unique index
            // refused the second. Nothing was written.
            when(service.create(any())).thenThrow(new DataIntegrityViolationException("uq_subscribers_email"));

            mvc.perform(json(post("/api/subscribers"), """
                    {"name":"Grace","email":"grace@navy.mil","planPriceId":"%s"}
                    """.formatted(UUID.randomUUID())))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message").value(not(containsString("uq_"))));
        }
    }

    @Nested
    @DisplayName("FR-03.7 / FR-03.3 editing and canceling")
    class Changing {

        @Test
        void anEditNamesTheSubscriberByItsAddress() throws Exception {
            UUID id = UUID.randomUUID();
            ArgumentCaptor<UpdateSubscriberCommand> sent = ArgumentCaptor.forClass(UpdateSubscriberCommand.class);
            when(service.update(sent.capture())).thenReturn(grace());

            mvc.perform(json(put("/api/subscribers/{id}", id), """
                    {"name":"Grace B. Hopper","email":"gbh@navy.mil"}
                    """))
                    .andExpect(status().isOk());

            assertThat(sent.getValue().id()).isEqualTo(id);
            assertThat(sent.getValue().email()).isEqualTo("gbh@navy.mil");
        }

        @Test
        void deletingCancelsAndTheRecordComesBack() throws Exception {
            UUID id = UUID.randomUUID();
            when(service.cancel(id)).thenReturn(grace());

            mvc.perform(delete("/api/subscribers/{id}", id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Grace"));
        }

        @Test
        void aMissingSubscriberIsNotFound() throws Exception {
            UUID id = UUID.randomUUID();
            when(service.findById(id)).thenThrow(new SubscriberNotFoundException(id));

            mvc.perform(get("/api/subscribers/{id}", id))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }
    }

    @Nested
    @DisplayName("FR-06.3 / FR-07 the listing")
    class Listing {

        @Test
        void thePageShowsEachSubscriberWithWhatItPays() throws Exception {
            when(service.search(any(SearchFilter.class), any()))
                    .thenReturn(new PageImpl<>(List.of(grace()), PageRequest.of(0, 20), 1));

            mvc.perform(get("/api/subscribers"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.items[0].currency").value("BRL"))
                    .andExpect(content().string(containsString("\"price\":49.90")));
        }

        @Test
        void anAbsentSortIsNewestFirst() throws Exception {
            ArgumentCaptor<Pageable> sent = ArgumentCaptor.forClass(Pageable.class);
            when(service.search(any(SearchFilter.class), sent.capture()))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

            mvc.perform(get("/api/subscribers")).andExpect(status().isOk());

            assertThat(sent.getValue().getSort().getOrderFor("startedAt")).isEqualTo(Sort.Order.desc("startedAt"));
        }

        @Test
        void theStatusFilterIsPassedThroughForTheIndexToJudge() throws Exception {
            ArgumentCaptor<SearchFilter> sent = ArgumentCaptor.forClass(SearchFilter.class);
            when(service.search(sent.capture(), any()))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

            mvc.perform(get("/api/subscribers").param("filter", "status:ACTIVE,PAST_DUE"))
                    .andExpect(status().isOk());

            assertThat(sent.getValue().valuesOf("status")).containsExactly("ACTIVE", "PAST_DUE");
        }
    }

    @Test
    void theRebuildReportsHowManySubscribersTheIndexHolds() throws Exception {
        when(service.reindex()).thenReturn(3L);

        mvc.perform(post("/api/subscribers/reindex"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexed").value(3));
    }

    private static SubscriberSummary grace() {
        Plan plan = Plan.create("Pro", null, NOW);
        PlanPrice price = plan.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW);
        return SubscriberSummary.of(Subscriber.start("Grace", "grace@navy.mil", price, NOW), plan);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
