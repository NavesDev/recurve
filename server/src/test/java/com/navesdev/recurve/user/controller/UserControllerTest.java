package com.navesdev.recurve.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.UncategorizedElasticsearchException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.navesdev.recurve.shared.controller.GlobalExceptionHandler;
import com.navesdev.recurve.shared.controller.ListingRequests;
import com.navesdev.recurve.user.domain.Permission;
import com.navesdev.recurve.user.domain.User;
import com.navesdev.recurve.user.domain.UserSummary;
import com.navesdev.recurve.user.service.CreateUserCommand;
import com.navesdev.recurve.shared.service.SearchFilter;
import com.navesdev.recurve.user.service.UserService;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.ErrorCause;
import co.elastic.clients.elasticsearch._types.ErrorResponse;

/**
 * What the API promises a client: the shape of a page, what counts as a
 * bad request, and that a password never travels back out. Authorization
 * needs the real service, so it lives in
 * {@code UserEndpointAuthorizationIT}.
 */
@WebMvcTest(UserController.class)
@Import({ GlobalExceptionHandler.class, ListingRequests.class, UserControllerTest.FixedClock.class })
class UserControllerTest {

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
    private UserService service;

    @Nested
    @DisplayName("FR-01.1 an operator is registered with name, email and password")
    class Registering {

        @Test
        void aCompleteRegistrationIsAccepted() throws Exception {
            when(service.create(any(CreateUserCommand.class))).thenReturn(operator());

            mvc.perform(register("""
                    {"name":"Ada","email":"ada@recurve.local","password":"s3cret-password"}
                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.email").value("ada@recurve.local"));
        }

        @Test
        void aRegistrationMissingTheNameIsRefused() throws Exception {
            mvc.perform(register("""
                    {"name":"","email":"ada@recurve.local","password":"s3cret-password"}
                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[*].field").value("name"));

            verify(service, never()).create(any());
        }

        @Test
        void anAddressThatIsNotAnEmailIsRefused() throws Exception {
            mvc.perform(register("""
                    {"name":"Ada","email":"not-an-email","password":"s3cret-password"}
                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[*].field").value("email"));

            verify(service, never()).create(any());
        }

        @Test
        void aPasswordTooShortToBeWorthHashingIsRefused() throws Exception {
            mvc.perform(register("""
                    {"name":"Ada","email":"ada@recurve.local","password":"short"}
                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[*].field").value("password"));

            verify(service, never()).create(any());
        }

        @Test
        void everyProblemWithTheRegistrationIsReportedAtOnce() throws Exception {
            mvc.perform(register("""
                    {"name":"","email":"not-an-email","password":"short"}
                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.length()").value(3));
        }
    }

    @Nested
    @DisplayName("A password never leaves the system")
    class PasswordNeverEscapes {

        @Test
        void neitherTheHashNorThePasswordIsEverSentBack() throws Exception {
            when(service.create(any(CreateUserCommand.class))).thenReturn(operator());

            mvc.perform(register("""
                    {"name":"Ada","email":"ada@recurve.local","password":"s3cret-password"}
                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.passwordHash").doesNotExist())
                    .andExpect(jsonPath("$.password").doesNotExist())
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("s3cret-password"))));
        }
    }

    @Nested
    @DisplayName("FR-07 a listing answers with a page")
    class Listing {

        @Test
        void thePageReportsItsItemsItsPositionAndTheTotal() throws Exception {
            when(service.search(any(SearchFilter.class), any()))
                    .thenReturn(new PageImpl<>(List.of(UserSummary.of(operator())), PageRequest.of(0, 20), 42));

            mvc.perform(get("/api/users"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.page").value(0))
                    .andExpect(jsonPath("$.size").value(20))
                    .andExpect(jsonPath("$.total").value(42));
        }

        @Test
        void theListingParametersAreVettedBeforeTheServiceIsAsked() throws Exception {
            // The rules themselves are ListingRequestsTest's; this only shows
            // the endpoint goes through them.
            mvc.perform(get("/api/users").param("size", "101"))
                    .andExpect(status().isBadRequest());

            verify(service, never()).search(any(), any());
        }

        @Test
        void theSortIsPassedThroughAsNamedWithIdAppendedForStablePaging() throws Exception {
            // The contract names the index's own fields (name.keyword, not
            // name): nothing here translates or vets them. FR-07.4: id is
            // always the second key, ascending whichever way the caller asked.
            ArgumentCaptor<Pageable> sent = ArgumentCaptor.forClass(Pageable.class);
            when(service.search(any(SearchFilter.class), any()))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

            mvc.perform(get("/api/users").param("sort", "email.keyword:desc"))
                    .andExpect(status().isOk());

            verify(service).search(any(), sent.capture());
            assertThat(sent.getValue().getSort()).containsExactly(
                    Sort.Order.desc("email.keyword"), Sort.Order.asc("id"));
        }

        @Test
        void anAbsentSortIsByNameAscending() throws Exception {
            ArgumentCaptor<Pageable> sent = ArgumentCaptor.forClass(Pageable.class);
            when(service.search(any(SearchFilter.class), any()))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

            mvc.perform(get("/api/users")).andExpect(status().isOk());

            verify(service).search(any(), sent.capture());
            assertThat(sent.getValue().getSort().getOrderFor("name.keyword")).isNotNull();
        }

        @Test
        void aSortTheIndexRefusesIsABadRequestWithTheReasonElasticsearchGave() throws Exception {
            // The mapping decides what can be sorted on; a refusal from the
            // engine is the validation, and its reason names the field.
            when(service.search(any(SearchFilter.class), any()))
                    .thenThrow(refusedBy("No mapping found for [passwordHash] in order to sort on"));

            mvc.perform(get("/api/users").param("sort", "passwordHash"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("passwordHash")));
        }

        @Test
        void anyOtherFailureOfTheSearchEngineIsNotBlamedOnTheCaller() throws Exception {
            when(service.search(any(SearchFilter.class), any()))
                    .thenThrow(new UncategorizedElasticsearchException("boom", 500, null, null));

            mvc.perform(get("/api/users"))
                    .andExpect(status().isBadGateway());
        }
    }

    @Nested
    @DisplayName("FR-06 filtering a listing")
    class Filtering {

        @Test
        void aFilterTheListingOffersIsAccepted() throws Exception {
            when(service.search(any(SearchFilter.class), any()))
                    .thenReturn(new PageImpl<>(List.of(UserSummary.of(operator())), PageRequest.of(0, 20), 1));

            mvc.perform(get("/api/users").param("filter", "active:true"))
                    .andExpect(status().isOk());
        }

        @Test
        void severalValuesOfOneFieldTravelTogetherInOneFilter() throws Exception {
            // A comma separates values inside one criterion; it must not be
            // mistaken for a separator between criteria.
            ArgumentCaptor<SearchFilter> sent = ArgumentCaptor.forClass(SearchFilter.class);
            when(service.search(any(SearchFilter.class), any()))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

            mvc.perform(get("/api/users").param("filter", "active:true,false"))
                    .andExpect(status().isOk());

            verify(service).search(sent.capture(), any());
            assertThat(sent.getValue().valuesOf("active")).containsExactly("true", "false");
        }

        @Test
        void severalFiltersAreAcceptedInOneRequest() throws Exception {
            when(service.search(any(SearchFilter.class), any()))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

            mvc.perform(get("/api/users")
                    .param("filter", "active:true")
                    .param("filter", "active:false"))
                    .andExpect(status().isOk());
        }

        @Test
        void aFilterFieldIsPassedThroughForTheIndexMappingToJudge() throws Exception {
            // Nothing here knows which fields exist: the mapping closes what
            // must stay closed, and a field it does not know matches nothing.
            ArgumentCaptor<SearchFilter> sent = ArgumentCaptor.forClass(SearchFilter.class);
            when(service.search(any(SearchFilter.class), any()))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

            mvc.perform(get("/api/users").param("filter", "permissions:MANAGE_SYSTEM"))
                    .andExpect(status().isOk());

            verify(service).search(sent.capture(), any());
            assertThat(sent.getValue().valuesOf("permissions")).containsExactly("MANAGE_SYSTEM");
        }

        @Test
        void aFilterTheIndexRefusesIsABadRequestWithTheReasonElasticsearchGave() throws Exception {
            when(service.search(any(SearchFilter.class), any()))
                    .thenThrow(refusedBy("Cannot search on field [permissions] since it is not indexed nor has doc values."));

            mvc.perform(get("/api/users").param("filter", "permissions:MANAGE_SYSTEM"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("permissions")));
        }

        @Test
        void aValueTheFieldCannotMeanIsRefusedByTheIndexNotHere() throws Exception {
            when(service.search(any(SearchFilter.class), any()))
                    .thenThrow(refusedBy("Failed to parse value [maybe] as only [true] or [false] are allowed."));

            mvc.perform(get("/api/users").param("filter", "active:maybe"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("maybe")));
        }

        @Test
        void aFilterThatIsNotWrittenAsFieldValueIsAValidationError() throws Exception {
            mvc.perform(get("/api/users").param("filter", "active"))
                    .andExpect(status().isBadRequest());

            verify(service, never()).search(any(), any());
        }
    }

    /** What Spring Data raises when Elasticsearch answers a search with 400. */
    private static UncategorizedElasticsearchException refusedBy(String reason) {
        ErrorResponse response = ErrorResponse.of(r -> r
                .status(400)
                .error(e -> e.type("search_phase_execution_exception")
                        .reason("all shards failed")
                        .rootCause(ErrorCause.of(c -> c.type("query_shard_exception").reason(reason)))));
        return new UncategorizedElasticsearchException(
                "Elasticsearch exception", 400, null, new ElasticsearchException("search", response));
    }

    private static org.springframework.test.web.servlet.RequestBuilder register(String body) {
        return post("/api/users").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Nested
    @DisplayName("FR-01.5 the search index is rebuilt on request")
    class Reindexing {

        @Test
        void theResponseSaysHowManyOperatorsWereIndexed() throws Exception {
            when(service.reindex()).thenReturn(42L);

            mvc.perform(post("/api/users/reindex"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.indexed").value(42));
        }
    }

    @Nested
    @DisplayName("Fail-fast: a store that cannot be reached is reported, not hidden")
    class StoreUnavailable {

        @Test
        void anUnreachableStoreIsAServiceUnavailableWithoutItsDetails() throws Exception {
            when(service.search(any(SearchFilter.class), any()))
                    .thenThrow(new DataAccessResourceFailureException("connect to localhost:9230 refused"));

            mvc.perform(get("/api/users"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.status").value(503))
                    .andExpect(jsonPath("$.message").value("A backing service is unavailable"));
        }
    }

    private static User operator() {
        return User.create("Ada", "ada@recurve.local", "$2a$10$hash",
                Set.of(Permission.MANAGE_USERS), NOW);
    }
}
