package com.navesdev.recurve.auth;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;
import com.navesdev.recurve.auth.service.TokenConfig;
import com.navesdev.recurve.auth.service.TokenProperties;
import com.navesdev.recurve.user.domain.Permission;
import com.navesdev.recurve.user.domain.User;
import com.navesdev.recurve.user.repository.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * FR-05: signing in, and a token standing in for the password on every
 * route, over the real security chain and database. The token names the
 * operator; their state and permissions are read on each request.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthenticationIT {

    private static final String PASSWORD = "s3cret-password";
    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtEncoder jwtEncoder;

    @PersistenceContext
    private EntityManager entityManager;

    private User planner;

    @BeforeEach
    void setUp() {
        entityManager.createNativeQuery("TRUNCATE users CASCADE").executeUpdate();
        planner = userRepository.save(User.create("Planner", "planner@recurve.local",
                passwordEncoder.encode(PASSWORD), Set.of(Permission.MANAGE_PLANS), NOW));
        entityManager.flush();
    }

    @Nested
    @DisplayName("FR-05.1 signing in")
    class SigningIn {

        @Test
        void rightCredentialsGetATokenWithNoOtherCredentialSent() throws Exception {
            mvc.perform(signIn("planner@recurve.local", PASSWORD))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.token").isString())
                    .andExpect(jsonPath("$.expiresAt").isString());
        }

        @Test
        void aWrongPasswordIsRefused() throws Exception {
            mvc.perform(signIn("planner@recurve.local", "wrong-password"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("Authentication required"));
        }

        @Test
        void anUnknownEmailReadsTheSameAsAWrongPassword() throws Exception {
            mvc.perform(signIn("ghost@recurve.local", PASSWORD))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("Authentication required"));
        }

        @Test
        void anInactiveOperatorGetsNoToken() throws Exception {
            planner.deactivate();
            entityManager.flush();

            mvc.perform(signIn("planner@recurve.local", PASSWORD)).andExpect(status().isUnauthorized());
        }

        @Test
        void neverInvitesABrowserLoginDialog() throws Exception {
            mvc.perform(signIn("planner@recurve.local", "wrong-password"))
                    .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
        }
    }

    @Nested
    @DisplayName("FR-05.3 a token on every route")
    class Bearer {

        @Test
        void opensWhatTheOperatorsPermissionsOpen() throws Exception {
            String token = tokenFor("planner@recurve.local");

            mvc.perform(get("/api/plans").with(bearer(token))).andExpect(status().isOk());
            mvc.perform(get("/api/subscribers").with(bearer(token))).andExpect(status().isForbidden());
        }

        @Test
        void aPermissionRevokedAfterSigningInIsGoneOnTheNextRequest() throws Exception {
            String token = tokenFor("planner@recurve.local");
            planner.replacePermissions(Set.of(Permission.VIEW_SUBSCRIBERS));
            entityManager.flush();

            mvc.perform(get("/api/plans").with(bearer(token))).andExpect(status().isForbidden());
            mvc.perform(get("/api/subscribers").with(bearer(token))).andExpect(status().isOk());
        }

        @Test
        void anOperatorDeactivatedAfterSigningInIsRefused() throws Exception {
            String token = tokenFor("planner@recurve.local");
            planner.deactivate();
            entityManager.flush();

            mvc.perform(get("/api/plans").with(bearer(token)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("Authentication required"));
        }

        @Test
        void anExpiredTokenIsRefused() throws Exception {
            String expired = sign(planner.getId().toString(), Instant.now().minusSeconds(120), Instant.now().minusSeconds(60));

            mvc.perform(get("/api/plans").with(bearer(expired))).andExpect(status().isUnauthorized());
        }

        @Test
        void aTokenSignedWithAnotherKeyIsRefused() throws Exception {
            TokenProperties other = new TokenProperties("another-key-another-key-another-key!", Duration.ofHours(1));
            JwtEncoder forger = new TokenConfig().jwtEncoder(other);
            String forged = forger.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                    JwtClaimsSet.builder().subject(planner.getId().toString())
                            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build()))
                    .getTokenValue();

            mvc.perform(get("/api/plans").with(bearer(forged))).andExpect(status().isUnauthorized());
        }

        @Test
        void aTokenForAnOperatorWhoDoesNotExistIsRefused() throws Exception {
            String orphan = sign(UUID.randomUUID().toString(), Instant.now(), Instant.now().plusSeconds(600));

            mvc.perform(get("/api/plans").with(bearer(orphan)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("Authentication required"));
        }

        @Test
        void garbageIsRefused() throws Exception {
            mvc.perform(get("/api/plans").with(bearer("not-a-token")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
        }
    }

    @Nested
    @DisplayName("FR-05.4 the signed-in operator")
    class Me {

        @Test
        void isAnsweredForATokenWithPermissionsExpanded() throws Exception {
            mvc.perform(get("/api/me").with(bearer(tokenFor("planner@recurve.local"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(planner.getId().toString()))
                    .andExpect(jsonPath("$.email").value("planner@recurve.local"))
                    .andExpect(jsonPath("$.permissions.length()").value(2));
        }

        @Test
        void isAnsweredForHttpBasicToo() throws Exception {
            mvc.perform(get("/api/me").with(httpBasic("planner@recurve.local", PASSWORD)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value("planner@recurve.local"));
        }

        @Test
        void needsNoPermission() throws Exception {
            userRepository.save(User.create("Nobody", "nobody@recurve.local",
                    passwordEncoder.encode(PASSWORD), Set.of(), NOW));
            entityManager.flush();

            mvc.perform(get("/api/me").with(bearer(tokenFor("nobody@recurve.local"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.permissions.length()").value(0));
        }

        @Test
        void isRefusedWithoutACredential() throws Exception {
            mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
        }
    }

    private static MockHttpServletRequestBuilder signIn(String email, String password) {
        return post("/api/auth/token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password));
    }

    private String tokenFor(String email) throws Exception {
        String body = mvc.perform(signIn(email, PASSWORD)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }

    private String sign(String subject, Instant issuedAt, Instant expiresAt) {
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().subject(subject).issuedAt(issuedAt).expiresAt(expiresAt).build()))
                .getTokenValue();
    }

    private static RequestPostProcessor bearer(String token) {
        return request -> {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            return request;
        };
    }
}
