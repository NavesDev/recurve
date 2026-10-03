package com.navesdev.recurve.auth.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.navesdev.recurve.auth.service.IssuedToken;
import com.navesdev.recurve.auth.service.SignInService;
import com.navesdev.recurve.shared.controller.GlobalExceptionHandler;
import com.navesdev.recurve.shared.controller.ListingRequests;
import com.navesdev.recurve.user.domain.Permission;
import com.navesdev.recurve.user.domain.User;
import com.navesdev.recurve.user.service.UserService;

/**
 * What signing in and asking "who am I" promise a client. Which routes
 * need a credential is {@code SecurityConfig}'s, covered in
 * {@code AuthenticationIT}.
 */
@WebMvcTest(AuthController.class)
@Import({ GlobalExceptionHandler.class, ListingRequests.class, AuthControllerTest.FixedClock.class })
class AuthControllerTest {

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
    private SignInService signInService;

    @MockitoBean
    private UserService userService;

    @Nested
    @DisplayName("FR-05.1 signing in")
    class SigningIn {

        @Test
        void rightCredentialsAnswerTheTokenAndWhenItExpires() throws Exception {
            when(signInService.signIn("ada@recurve.local", "s3cret-password"))
                    .thenReturn(new IssuedToken("signed.jwt.value", Instant.parse("2026-01-15T18:00:00Z")));

            mvc.perform(post("/api/auth/token").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"ada@recurve.local\",\"password\":\"s3cret-password\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.token").value("signed.jwt.value"))
                    .andExpect(jsonPath("$.expiresAt").value("2026-01-15T18:00:00Z"));
        }

        @Test
        void missingFieldsAreABadRequestNamingThem() throws Exception {
            mvc.perform(post("/api/auth/token").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\" \"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field == 'email')]").exists())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field == 'password')]").exists());
            verifyNoInteractions(signInService);
        }

        @Test
        void wrongCredentialsAndAnInactiveOperatorReadTheSame() throws Exception {
            when(signInService.signIn(anyString(), anyString()))
                    .thenThrow(new BadCredentialsException("Bad credentials"))
                    .thenThrow(new DisabledException("User is disabled"));

            for (int attempt = 0; attempt < 2; attempt++) {
                mvc.perform(post("/api/auth/token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ada@recurve.local\",\"password\":\"wrong\"}"))
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.message").value("Authentication required"));
            }
        }
    }

    @Nested
    @DisplayName("FR-05.4 the signed-in operator")
    class Me {

        @Test
        void answersWhoTheyAreAndTheirPermissionsExpanded() throws Exception {
            User ada = User.create("Ada", "ada@recurve.local", "hash", Set.of(Permission.MANAGE_PLANS), NOW);
            when(userService.findByEmail("ada@recurve.local")).thenReturn(ada);

            mvc.perform(get("/api/me").principal(new TestingAuthenticationToken("ada@recurve.local", null)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(ada.getId().toString()))
                    .andExpect(jsonPath("$.name").value("Ada"))
                    .andExpect(jsonPath("$.email").value("ada@recurve.local"))
                    .andExpect(jsonPath("$.permissions.length()").value(2))
                    .andExpect(jsonPath("$.permissions[0]").value("VIEW_PLANS"))
                    .andExpect(jsonPath("$.permissions[1]").value("MANAGE_PLANS"))
                    .andExpect(jsonPath("$.passwordHash").doesNotExist());
        }
    }
}
