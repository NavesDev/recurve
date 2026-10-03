package com.navesdev.recurve.auth.service;

import java.time.Instant;

/** A signed token and the moment it stops being accepted. */
public record IssuedToken(String token, Instant expiresAt) {
}
