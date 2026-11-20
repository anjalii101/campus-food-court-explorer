package com.campusfoodcourt.campus_foodcourt.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Base64;

/**
 * Access-token settings. Bound from {@code app.jwt.*}, the same keys {@link
 * com.campusfoodcourt.campus_foodcourt.security.JwtUtils} reads, so the application
 * fails fast at startup when the signing key is missing or too weak.
 */
@Validated
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(@NotBlank String secret, @Positive long expirationMs) {

    /** HS256 requires a key of at least 256 bits. */
    public static final int MIN_KEY_BYTES = 32;

    public JwtProperties {
        if (secret != null && !secret.isBlank()) {
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(secret.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("app.jwt.secret (JWT_SECRET) must be Base64-encoded", e);
            }
            if (decoded.length < MIN_KEY_BYTES) {
                throw new IllegalArgumentException("app.jwt.secret (JWT_SECRET) must decode to at least "
                        + MIN_KEY_BYTES + " bytes, but was " + decoded.length);
            }
        }
    }
}
