package com.navesdev.recurve.auth.controller;

import java.time.Instant;

import com.navesdev.recurve.auth.service.IssuedToken;

public record TokenResponse(String token, Instant expiresAt) {

    public static TokenResponse from(IssuedToken issued) {
        return new TokenResponse(issued.token(), issued.expiresAt());
    }
}
