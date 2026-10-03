package com.navesdev.recurve.auth.service;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Service;

import com.navesdev.recurve.user.service.UserService;

import lombok.RequiredArgsConstructor;

/**
 * FR-05.1: email and password for a token. The credentials go through the
 * same {@link AuthenticationManager} HTTP Basic uses, so an inactive
 * operator (BR-09) is refused here as there. A refusal is Spring
 * Security's {@code AuthenticationException}, the boundary's own language,
 * and is let through untouched for it to answer 401.
 */
@Service
@RequiredArgsConstructor
public class SignInService {

    private final AuthenticationManager authenticationManager;
    private final UserService userService;
    private final TokenIssuer issuer;

    public IssuedToken signIn(String email, String password) {
        authenticationManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email, password));
        return issuer.issue(userService.findByEmail(email));
    }
}
