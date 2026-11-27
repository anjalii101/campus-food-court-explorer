package com.campusfoodcourt.campus_foodcourt.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Base64;

/**
 * Access-token settings, bound from {@code app.jwt.*}. This record is the <b>only</b> source of JWT
 * configuration: {@link com.campusfoodcourt.campus_foodcourt.security.JwtUtils} receives it by injection
 * and has no defaults of its own, so the application fails fast at startup when the key is missing or weak.
 *
 * @param secret       Base64-encoded HMAC key; must decode to at least {@value #MIN_KEY_BYTES} bytes (256 bits)
 * @param expirationMs access-token lifetime in milliseconds (15 minutes by default, see application.properties)
 * @param issuer       value of the {@code iss} claim written into, and required from, every access token
 */
@Validated
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(@NotBlank String secret, @Positive long expirationMs, @NotBlank String issuer) {

    /** HS256 requires a key of at least 256 bits. */
    public static final int MIN_KEY_BYTES = 32;

    public JwtProperties {
        if (secret != null && !secret.isBlank()) {
            byte[] decoded;
            try {
                decoded = decode(secret);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("app.jwt.secret (JWT_SECRET) must be Base64-encoded", e);
            }
            if (decoded.length < MIN_KEY_BYTES) {
                throw new IllegalArgumentException("app.jwt.secret (JWT_SECRET) must decode to at least "
                        + MIN_KEY_BYTES + " bytes, but was " + decoded.length);
            }
        }
    }

    /**
     * The raw HMAC key bytes, decoded exactly as they were validated (surrounding whitespace ignored).
     * Returns a fresh copy on every call.
     */
    public byte[] decodedSecret() {
        return decode(secret);
    }

    private static byte[] decode(String secret) {
        return Base64.getDecoder().decode(secret.trim());
    }
}
