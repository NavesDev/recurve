package com.navesdev.recurve.auth.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import com.navesdev.recurve.user.domain.User;

import lombok.RequiredArgsConstructor;

/**
 * Signs a token naming an operator (FR-05.3). It carries the operator's id
 * and its lifetime, nothing more: what the operator may do is read from
 * the database on every request, so a permission never outlives its
 * revocation inside a token.
 */
@Component
@RequiredArgsConstructor
public class TokenIssuer {

    private final JwtEncoder encoder;
    private final TokenProperties properties;
    private final Clock clock;

    public IssuedToken issue(User operator) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.ttl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(operator.getId().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, expiresAt);
    }
}
