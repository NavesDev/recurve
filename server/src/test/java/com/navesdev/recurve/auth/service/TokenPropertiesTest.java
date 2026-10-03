package com.navesdev.recurve.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/** Fail-closed: a key anyone could guess, or no key, stops the startup. */
class TokenPropertiesTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void aSecretOfThirtyTwoBytesIsAccepted() {
        TokenProperties properties = new TokenProperties(SECRET, Duration.ofHours(8));

        assertThat(properties.key().getAlgorithm()).isEqualTo("HmacSHA256");
        assertThat(properties.key().getEncoded()).hasSize(32);
    }

    @Test
    void aMissingSecretIsRefused() {
        assertThatThrownBy(() -> new TokenProperties(null, Duration.ofHours(8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recurve.auth.token.secret");
        assertThatThrownBy(() -> new TokenProperties("  ", Duration.ofHours(8)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSecretShorterThanThirtyTwoBytesIsRefused() {
        assertThatThrownBy(() -> new TokenProperties(SECRET.substring(1), Duration.ofHours(8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void aLifetimeThatIsNotPositiveIsRefused() {
        assertThatThrownBy(() -> new TokenProperties(SECRET, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recurve.auth.token.ttl");
        assertThatThrownBy(() -> new TokenProperties(SECRET, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSecretIsNeverPrinted() {
        assertThat(new TokenProperties(SECRET, Duration.ofHours(8)).toString()).doesNotContain(SECRET);
    }
}
