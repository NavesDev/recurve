package com.navesdev.recurve.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import com.navesdev.recurve.user.domain.Permission;
import com.navesdev.recurve.user.domain.User;

/** FR-05.3: the token names the operator, and nothing they may do. */
class TokenIssuerTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    private static final TokenProperties PROPERTIES =
            new TokenProperties("0123456789abcdef0123456789abcdef", Duration.ofHours(8));

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final TokenConfig config = new TokenConfig();
    private final TokenIssuer issuer = new TokenIssuer(config.jwtEncoder(PROPERTIES), PROPERTIES, clock);
    private final JwtDecoder decoder = config.jwtDecoder(PROPERTIES, clock);

    private final User ada = User.create("Ada", "ada@recurve.local", "hash", Set.of(Permission.MANAGE_PLANS), NOW);

    @Test
    void theSubjectIsTheOperatorsId() {
        Jwt jwt = decoder.decode(issuer.issue(ada).token());

        assertThat(jwt.getSubject()).isEqualTo(ada.getId().toString());
    }

    @Test
    void itExpiresAfterTheConfiguredLifetime() {
        IssuedToken issued = issuer.issue(ada);
        Jwt jwt = decoder.decode(issued.token());

        assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(8)));
        assertThat(jwt.getIssuedAt()).isEqualTo(NOW);
        assertThat(jwt.getExpiresAt()).isEqualTo(issued.expiresAt());
    }

    @Test
    void itCarriesNoPermission() {
        Jwt jwt = decoder.decode(issuer.issue(ada).token());

        assertThat(jwt.getClaims()).containsOnlyKeys("sub", "iat", "exp");
    }
}
