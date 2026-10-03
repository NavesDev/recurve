package com.navesdev.recurve.auth.service;

import java.util.UUID;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.navesdev.recurve.user.domain.User;
import com.navesdev.recurve.user.domain.exception.UserNotFoundException;
import com.navesdev.recurve.user.service.UserService;

import lombok.RequiredArgsConstructor;

/**
 * Turns a verified token into the principal, reading the operator as they
 * are now: the token only says who (FR-05.3). Fail-closed — a subject
 * that is not an id, an operator who is gone or inactive (BR-09) is an
 * authentication failure, translated here so the {@code user} feature's
 * exceptions never decide an authentication status. Every refusal reads
 * the same, so a caller learns nothing about which part was wrong.
 *
 * <p>Declared in {@link TokenConfig}, not scanned: a scanned
 * {@code Converter} would be picked up by every web slice test.
 */
@RequiredArgsConstructor
public class OperatorTokenConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final String REFUSED = "Invalid token";

    private final UserService userService;

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        User operator = load(jwt.getSubject());
        if (!operator.isActive()) {
            throw new InvalidBearerTokenException(REFUSED);
        }
        return new JwtAuthenticationToken(jwt,
                operator.authorities().stream().map(permission -> new SimpleGrantedAuthority(permission.name())).toList(),
                operator.getEmail());
    }

    private User load(String subject) {
        try {
            return userService.findById(UUID.fromString(subject));
        } catch (IllegalArgumentException | NullPointerException | UserNotFoundException e) {
            throw new InvalidBearerTokenException(REFUSED, e);
        }
    }
}
