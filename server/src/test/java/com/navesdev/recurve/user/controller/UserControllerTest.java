package com.navesdev.recurve.user.controller;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.navesdev.recurve.shared.controller.GlobalExceptionHandler;
import com.navesdev.recurve.user.domain.Permission;
import com.navesdev.recurve.user.domain.User;
import com.navesdev.recurve.user.service.CreateUserCommand;
import com.navesdev.recurve.user.service.UserFilter;
import com.navesdev.recurve.user.service.UserService;

/**
 * What the API promises a client: the shape of a page, what counts as a
 * bad request, and that a password never travels back out. Authorization
 * needs the real service, so it lives in
 * {@code UserEndpointAuthorizationTest}.
 */
@WebMvcTest(UserController.class)
@Import({ GlobalExceptionHandler.class, UserControllerTest.FixedClock.class })
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
            when(service.search(any(UserFilter.class), any()))
                    .thenReturn(new PageImpl<>(List.of(operator()), PageRequest.of(0, 20), 42));

            mvc.perform(get("/api/users"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.page").value(0))
                    .andExpect(jsonPath("$.size").value(20))
                    .andExpect(jsonPath("$.total").value(42));
        }

        @Test
        void askingForMoreThanTheMaximumPageSizeIsAValidationError() throws Exception {
            mvc.perform(get("/api/users").param("size", "101"))
                    .andExpect(status().isBadRequest());

            verify(service, never()).search(any(), any());
        }

        @Test
        void askingForAnEmptyPageIsAValidationError() throws Exception {
            mvc.perform(get("/api/users").param("size", "0"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void askingForAPageBeforeTheFirstIsAValidationError() throws Exception {
            mvc.perform(get("/api/users").param("page", "-1"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void sortingByAFieldOutsideTheAllowedListIsAValidationError() throws Exception {
            mvc.perform(get("/api/users").param("sort", "passwordHash"))
                    .andExpect(status().isBadRequest());

            verify(service, never()).search(any(), any());
        }

        @Test
        void aDescendingSortOverAnAllowedFieldIsAccepted() throws Exception {
            when(service.search(any(UserFilter.class), any()))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

            mvc.perform(get("/api/users").param("sort", "-email"))
                    .andExpect(status().isOk());
        }

        @Test
        void namingMoreThanOneFieldIsAValidationErrorNotASilentChoiceOfOne() throws Exception {
            // A listing sorts on one field (FR-06). Quietly honouring the
            // first and dropping the rest would answer a question nobody
            // asked, and the caller would never learn it happened.
            mvc.perform(get("/api/users").param("sort", "name,email"))
                    .andExpect(status().isBadRequest());

            mvc.perform(get("/api/users").param("sort", "name").param("sort", "email"))
                    .andExpect(status().isBadRequest());

            verify(service, never()).search(any(), any());
        }

        @Test
        void aDescendingSortOverAForbiddenFieldIsStillAValidationError() throws Exception {
            mvc.perform(get("/api/users").param("sort", "-passwordHash"))
                    .andExpect(status().isBadRequest());

            verify(service, never()).search(any(), any());
        }
    }

    @Nested
    @DisplayName("FR-06 filtering a listing")
    class Filtering {

        @Test
        void aFilterTheListingOffersIsAccepted() throws Exception {
            when(service.search(any(UserFilter.class), any()))
                    .thenReturn(new PageImpl<>(List.of(operator()), PageRequest.of(0, 20), 1));

            mvc.perform(get("/api/users").param("filter", "active:true"))
                    .andExpect(status().isOk());
        }

        @Test
        void severalValuesOfOneFieldTravelTogetherInOneFilter() throws Exception {
            // A comma separates values inside one criterion; it must not be
            // mistaken for a separator between criteria.
            org.mockito.ArgumentCaptor<UserFilter> sent =
                    org.mockito.ArgumentCaptor.forClass(UserFilter.class);
            when(service.search(any(UserFilter.class), any()))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

            mvc.perform(get("/api/users").param("filter", "active:true,false"))
                    .andExpect(status().isOk());

            verify(service).search(sent.capture(), any());
            org.assertj.core.api.Assertions
                    .assertThat(sent.getValue().valuesOf(
                            com.navesdev.recurve.user.service.UserFilterField.ACTIVE))
                    .containsExactly("true", "false");
        }

        @Test
        void severalFiltersAreAcceptedInOneRequest() throws Exception {
            when(service.search(any(UserFilter.class), any()))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

            mvc.perform(get("/api/users")
                    .param("filter", "active:true")
                    .param("filter", "active:false"))
                    .andExpect(status().isOk());
        }

        @Test
        void aFieldTheListingDoesNotOfferIsAValidationErrorNotAnEmptyPage() throws Exception {
            mvc.perform(get("/api/users").param("filter", "passwordHash:$2a$10$x"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("filter must be one of")));

            verify(service, never()).search(any(), any());
        }

        @Test
        void aValueTheFieldCannotMeanIsAValidationError() throws Exception {
            mvc.perform(get("/api/users").param("filter", "active:maybe"))
                    .andExpect(status().isBadRequest());

            verify(service, never()).search(any(), any());
        }

        @Test
        void aFilterThatIsNotWrittenAsFieldValueIsAValidationError() throws Exception {
            mvc.perform(get("/api/users").param("filter", "active"))
                    .andExpect(status().isBadRequest());

            verify(service, never()).search(any(), any());
        }
    }

    private static org.springframework.test.web.servlet.RequestBuilder register(String body) {
        return post("/api/users").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static User operator() {
        return User.create("Ada", "ada@recurve.local", "$2a$10$hash",
                Set.of(Permission.MANAGE_USERS), NOW);
    }
}
