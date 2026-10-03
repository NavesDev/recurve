package com.navesdev.recurve.auth.service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How operator tokens are signed (FR-05.3; NFR-03: from the environment).
 * Checked at startup, fail-closed: no key, or one short enough to guess,
 * and the application does not start — an unsigned or weakly signed token
 * would let anyone be anyone.
 *
 * @param secret the HS256 key, at least 32 bytes as UTF-8
 * @param ttl how long a token lives; no refresh, the operator signs in again
 */
@ConfigurationProperties("recurve.auth.token")
public record TokenProperties(String secret, Duration ttl) {

    /** HS256 needs a key at least as long as its 256-bit output. */
    private static final int MIN_SECRET_BYTES = 32;
    private static final String ALGORITHM = "HmacSHA256";

    public TokenProperties {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("recurve.auth.token.secret is required (JWT_SECRET)");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "recurve.auth.token.secret must be at least " + MIN_SECRET_BYTES + " bytes");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("recurve.auth.token.ttl must be a positive duration");
        }
    }

    public SecretKey key() {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    /** Never prints the secret. */
    @Override
    public String toString() {
        return "TokenProperties[ttl=" + ttl + "]";
    }
}
