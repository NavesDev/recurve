package com.navesdev.recurve.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import com.navesdev.recurve.user.domain.User;
import com.navesdev.recurve.user.service.UserService;

/**
 * FR-05.1: the credentials are checked by the same authentication Basic
 * uses, and only then is a token issued. A refusal is Spring Security's
 * own exception, left for the boundary to answer.
 */
@ExtendWith(MockitoExtension.class)
class SignInServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private UserService userService;

    @Mock
    private TokenIssuer issuer;

    private SignInService service;

    @BeforeEach
    void setUp() {
        service = new SignInService(authenticationManager, userService, issuer);
    }

    @Test
    void rightCredentialsGetATokenForThatOperator() {
        User ada = User.create("Ada", "ada@recurve.local", "hash", Set.of(), NOW);
        IssuedToken token = new IssuedToken("signed", NOW.plusSeconds(60));
        when(authenticationManager.authenticate(argThat(credentials ->
                "ada@recurve.local".equals(credentials.getName())
                        && "s3cret-password".equals(credentials.getCredentials()))))
                .thenReturn(UsernamePasswordAuthenticationToken.authenticated("ada@recurve.local", null, Set.of()));
        when(userService.findByEmail("ada@recurve.local")).thenReturn(ada);
        when(issuer.issue(ada)).thenReturn(token);

        assertThat(service.signIn("ada@recurve.local", "s3cret-password")).isSameAs(token);
    }

    @Test
    void wrongCredentialsGetNoToken() {
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("Bad credentials"));

        assertThatThrownBy(() -> service.signIn("ada@recurve.local", "wrong"))
                .isInstanceOf(BadCredentialsException.class);
        verifyNoInteractions(issuer);
    }

    @Test
    void anInactiveOperatorGetsNoToken() {
        when(authenticationManager.authenticate(any())).thenThrow(new DisabledException("User is disabled"));

        assertThatThrownBy(() -> service.signIn("ada@recurve.local", "s3cret-password"))
                .isInstanceOf(DisabledException.class);
        verifyNoInteractions(issuer);
    }
}
