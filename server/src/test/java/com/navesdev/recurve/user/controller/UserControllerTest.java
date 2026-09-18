package com.navesdev.recurve.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
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
 * The service is mocked, so this covers request validation, serialization
 * and HTTP status only — authorization is exercised where the real service
 * is, in {@code UserEndpointAuthorizationTest}.
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

    @Test
    void createsAndReturnsTheLocation() throws Exception {
        when(service.create(any(CreateUserCommand.class))).thenReturn(operator());

        mvc.perform(post("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "name": "Ada",
                          "email": "ada@recurve.local",
                          "password": "s3cret-password",
                          "permissions": ["MANAGE_USERS"]
                        }
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Ada"))
                .andExpect(jsonPath("$.email").value("ada@recurve.local"));
    }

    @Test
    void neverSerializesThePasswordHash() throws Exception {
        when(service.create(any(CreateUserCommand.class))).thenReturn(operator());

        mvc.perform(post("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Ada","email":"ada@recurve.local","password":"s3cret-password"}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void rejectsAnInvalidBodyWithFieldErrors() throws Exception {
        mvc.perform(post("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"","email":"not-an-email","password":"short"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").isArray());

        verify(service, never()).create(any());
    }

    @Test
    void returnsAPageEnvelope() throws Exception {
        when(service.search(any(UserFilter.class), any()))
                .thenReturn(new PageImpl<>(List.of(operator()), PageRequest.of(0, 20), 1));

        mvc.perform(get("/api/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void rejectsAPageSizeOverTheMaximum() throws Exception {
        mvc.perform(get("/api/users").param("size", "101"))
                .andExpect(status().isBadRequest());

        verify(service, never()).search(any(), any());
    }

    @Test
    void rejectsASortFieldOutsideTheAllowedList() throws Exception {
        mvc.perform(get("/api/users").param("sort", "passwordHash"))
                .andExpect(status().isBadRequest());

        verify(service, never()).search(any(), any());
    }

    private static User operator() {
        return User.create("Ada", "ada@recurve.local", "hash", Set.of(Permission.MANAGE_USERS), NOW);
    }
}
