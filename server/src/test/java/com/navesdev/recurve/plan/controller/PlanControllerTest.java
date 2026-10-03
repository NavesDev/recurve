package com.navesdev.recurve.plan.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.navesdev.recurve.plan.domain.BillingInterval;
import com.navesdev.recurve.plan.domain.Plan;
import com.navesdev.recurve.plan.domain.PlanSummary;
import com.navesdev.recurve.plan.domain.exception.PlanNotFoundException;
import com.navesdev.recurve.plan.domain.exception.PriceAlreadyActiveException;
import com.navesdev.recurve.plan.service.AddPriceCommand;
import com.navesdev.recurve.plan.service.CreatePlanCommand;
import com.navesdev.recurve.plan.service.PlanService;
import com.navesdev.recurve.plan.service.UpdatePlanCommand;
import com.navesdev.recurve.shared.controller.GlobalExceptionHandler;
import com.navesdev.recurve.shared.controller.ListingRequests;
import com.navesdev.recurve.shared.service.SearchFilter;

/**
 * What the plan API promises a client: what counts as a bad request, how
 * an amount travels, and the shape of a page. Authorization needs the
 * real chain, so it lives in {@code PlanEndpointAuthorizationIT}.
 */
@WebMvcTest(PlanController.class)
@Import({ GlobalExceptionHandler.class, ListingRequests.class, PlanControllerTest.FixedClock.class })
class PlanControllerTest {

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
    @DisplayName("FR-02.1 / FR-02.6 a plan has a name and may have a description")
    class Plans {

        @Test
        void aRegisteredPlanIsCreatedAtItsOwnAddress() throws Exception {
            Plan plan = Plan.create("Pro", "For teams", NOW);
            when(service.create(any(CreatePlanCommand.class))).thenReturn(plan);

            mvc.perform(json(post("/api/plans"), """
                    {"name":"Pro","description":"For teams"}
                    """))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", "/api/plans/" + plan.getId()))
                    .andExpect(jsonPath("$.name").value("Pro"))
                    .andExpect(jsonPath("$.active").value(true))
                    .andExpect(jsonPath("$.prices.length()").value(0));
        }

        @Test
        void aPlanWithoutANameIsRefused() throws Exception {
            mvc.perform(json(post("/api/plans"), """
                    {"name":""}
                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[*].field").value("name"));

            verify(service, never()).create(any());
        }

        @Test
        void aDescriptionLongerThanItsColumnIsRefused() throws Exception {
            mvc.perform(json(post("/api/plans"), """
                    {"name":"Pro","description":"%s"}
                    """.formatted("a".repeat(501))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[*].field").value("description"));
        }

        @Test
        void anEditNamesThePlanByItsAddress() throws Exception {
            UUID id = UUID.randomUUID();
            ArgumentCaptor<UpdatePlanCommand> sent = ArgumentCaptor.forClass(UpdatePlanCommand.class);
            when(service.update(sent.capture())).thenReturn(Plan.create("Pro Plus", null, NOW));

            mvc.perform(json(put("/api/plans/{id}", id), """
                    {"name":"Pro Plus"}
                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.description").doesNotExist());

            assertThat(sent.getValue().id()).isEqualTo(id);
        }

        @Test
        void aMissingPlanIsNotFound() throws Exception {
            UUID id = UUID.randomUUID();
            when(service.findById(id)).thenThrow(new PlanNotFoundException(id));

            mvc.perform(get("/api/plans/{id}", id))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }
    }

    @Nested
    @DisplayName("FR-02.2 pricing a plan")
    class Pricing {

        @Test
        void aPriceIsCreatedUnderItsPlanAndThePlanComesBack() throws Exception {
            UUID planId = UUID.randomUUID();
            ArgumentCaptor<AddPriceCommand> sent = ArgumentCaptor.forClass(AddPriceCommand.class);
            when(service.addPrice(sent.capture())).thenReturn(pricedPlan());

            mvc.perform(json(post("/api/plans/{id}/prices", planId), """
                    {"price":49.90,"currency":"BRL","interval":"MONTHLY"}
                    """))
                    .andExpect(status().isCreated())
                    .andExpect(header().doesNotExist("Location"))
                    .andExpect(jsonPath("$.prices[0].currency").value("BRL"))
                    .andExpect(jsonPath("$.prices[0].interval").value("MONTHLY"));

            assertThat(sent.getValue().planId()).isEqualTo(planId);
            assertThat(sent.getValue().price()).isEqualByComparingTo("49.90");
        }

        @Test
        void anAmountTravelsWithItsTwoDecimalPlaces() throws Exception {
            // NFR-06: 49.90 goes out as 49.90, not 49.9.
            when(service.addPrice(any())).thenReturn(pricedPlan());

            mvc.perform(json(post("/api/plans/{id}/prices", UUID.randomUUID()), """
                    {"price":49.90,"currency":"BRL","interval":"MONTHLY"}
                    """))
                    .andExpect(content().string(containsString("\"price\":49.90")));
        }

        @Test
        void anAmountThatIsNotPositiveIsRefused() throws Exception {
            mvc.perform(json(post("/api/plans/{id}/prices", UUID.randomUUID()), """
                    {"price":0,"currency":"BRL","interval":"MONTHLY"}
                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[*].field").value("price"));

            verify(service, never()).addPrice(any());
        }

        @Test
        void anAmountWithMoreThanTwoDecimalPlacesIsRefused() throws Exception {
            mvc.perform(json(post("/api/plans/{id}/prices", UUID.randomUUID()), """
                    {"price":12.345,"currency":"BRL","interval":"MONTHLY"}
                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[*].field").value("price"));
        }

        @Test
        void everyProblemWithThePriceIsReportedAtOnce() throws Exception {
            mvc.perform(json(post("/api/plans/{id}/prices", UUID.randomUUID()), """
                    {"price":-1,"currency":"BR"}
                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.length()").value(3));
        }

        @Test
        void aSecondActivePriceOnOnePairIsABusinessRuleViolation() throws Exception {
            when(service.addPrice(any()))
                    .thenThrow(new PriceAlreadyActiveException(BillingInterval.MONTHLY, "BRL"));

            mvc.perform(json(post("/api/plans/{id}/prices", UUID.randomUUID()), """
                    {"price":59.90,"currency":"BRL","interval":"MONTHLY"}
                    """))
                    .andExpect(status().isUnprocessableEntity());
        }
    }

    @Nested
    @DisplayName("A plan changed by someone else first")
    class Conflict {

        @Test
        void aLostRaceIsAConflictTheCallerCanRetry() throws Exception {
            when(service.update(any())).thenThrow(new ObjectOptimisticLockingFailureException(Plan.class, UUID.randomUUID()));

            mvc.perform(json(put("/api/plans/{id}", UUID.randomUUID()), """
                    {"name":"Pro"}
                    """))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));
        }
    }

    @Nested
    @DisplayName("FR-06.2 / FR-07 the listing")
    class Listing {

        @Test
        void thePageShowsEachPlanWithItsPrices() throws Exception {
            when(service.search(any(SearchFilter.class), any()))
                    .thenReturn(new PageImpl<>(List.of(PlanSummary.of(pricedPlan())), PageRequest.of(0, 20), 1));

            mvc.perform(get("/api/plans"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.items[0].prices[0].currency").value("BRL"))
                    .andExpect(content().string(containsString("\"price\":49.90")));
        }

        @Test
        void anAbsentSortIsByNameAscending() throws Exception {
            ArgumentCaptor<Pageable> sent = ArgumentCaptor.forClass(Pageable.class);
            when(service.search(any(SearchFilter.class), sent.capture()))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

            mvc.perform(get("/api/plans")).andExpect(status().isOk());

            assertThat(sent.getValue().getSort().getOrderFor("name.keyword")).isNotNull();
        }

        @Test
        void theCycleFilterIsPassedThroughForTheIndexToJudge() throws Exception {
            ArgumentCaptor<SearchFilter> sent = ArgumentCaptor.forClass(SearchFilter.class);
            when(service.search(sent.capture(), any()))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

            mvc.perform(get("/api/plans").param("filter", "activeIntervals:MONTHLY,YEARLY"))
                    .andExpect(status().isOk());

            assertThat(sent.getValue().valuesOf("activeIntervals")).containsExactly("MONTHLY", "YEARLY");
        }
    }

    @Test
    void theRebuildReportsHowManyPlansTheIndexHolds() throws Exception {
        when(service.reindex()).thenReturn(3L);

        mvc.perform(post("/api/plans/reindex"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexed").value(3));
    }

    private static Plan pricedPlan() {
        Plan plan = Plan.create("Pro", null, NOW);
        plan.addPrice(new BigDecimal("49.90"), "BRL", BillingInterval.MONTHLY, NOW);
        return plan;
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
