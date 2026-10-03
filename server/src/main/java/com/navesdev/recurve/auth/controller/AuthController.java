package com.navesdev.recurve.auth.controller;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.navesdev.recurve.auth.service.SignInService;
import com.navesdev.recurve.user.service.UserService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Signing in and asking who is signed in. Holds no rule; a refused sign-in
 * is Spring Security's exception, answered 401 by
 * {@code GlobalExceptionHandler}.
 */
@RestController
@RequiredArgsConstructor
public class AuthController {

    private final SignInService signInService;
    private final UserService userService;

    /** FR-05.1 and FR-05.3. */
    @PostMapping("/api/auth/token")
    public TokenResponse signIn(@Valid @RequestBody TokenRequest request) {
        return TokenResponse.from(signInService.signIn(request.email(), request.password()));
    }

    /** FR-05.4: the principal's name is the operator's email, whichever credential was used. */
    @GetMapping("/api/me")
    public MeResponse me(Authentication authentication) {
        return MeResponse.from(userService.findByEmail(authentication.getName()));
    }
}
