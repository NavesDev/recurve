package com.navesdev.recurve.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import com.navesdev.recurve.user.domain.Permission;
import com.navesdev.recurve.user.domain.User;
import com.navesdev.recurve.user.domain.exception.UserNotFoundException;
import com.navesdev.recurve.user.service.UserService;

/**
 * The token says who; the database says whether they still may. Every
 * doubt is an authentication failure — a 401 — never a domain exception.
 */
@ExtendWith(MockitoExtension.class)
class OperatorTokenConverterTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    @Mock
    private UserService userService;

    private OperatorTokenConverter converter;

    @BeforeEach
    void setUp() {
        converter = new OperatorTokenConverter(userService);
    }

    @Test
    void theOperatorsCurrentPermissionsBecomeTheAuthoritiesExpanded() {
        User ada = User.create("Ada", "ada@recurve.local", "hash", Set.of(Permission.MANAGE_PLANS), NOW);
        when(userService.findById(ada.getId())).thenReturn(ada);

        AbstractAuthenticationToken authentication = converter.convert(token(ada.getId().toString()));

        assertThat(authentication.getName()).isEqualTo("ada@recurve.local");
        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("MANAGE_PLANS", "VIEW_PLANS");
    }

    @Test
    void anInactiveOperatorIsRefusedThoughTheTokenIsValid() {
        User ada = User.create("Ada", "ada@recurve.local", "hash", Set.of(Permission.MANAGE_PLANS), NOW);
        ada.deactivate();
        when(userService.findById(ada.getId())).thenReturn(ada);

        assertThatThrownBy(() -> converter.convert(token(ada.getId().toString())))
                .isInstanceOf(AuthenticationException.class);
    }

    @Test
    void anOperatorWhoNoLongerExistsIsAnAuthenticationFailureNotANotFound() {
        UUID id = UUID.randomUUID();
        when(userService.findById(id)).thenThrow(new UserNotFoundException(id));

        assertThatThrownBy(() -> converter.convert(token(id.toString())))
                .isInstanceOf(AuthenticationException.class);
    }

    @Test
    void aSubjectThatIsNotAnIdIsRefusedWithoutALookup() {
        assertThatThrownBy(() -> converter.convert(token("ada@recurve.local")))
                .isInstanceOf(AuthenticationException.class);
        verifyNoInteractions(userService);
    }

    private static Jwt token(String subject) {
        return Jwt.withTokenValue("token").header("alg", "HS256").subject(subject)
                .issuedAt(NOW).expiresAt(NOW.plusSeconds(60)).build();
    }
}
