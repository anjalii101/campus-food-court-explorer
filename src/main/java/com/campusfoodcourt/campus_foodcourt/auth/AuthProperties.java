package com.campusfoodcourt.campus_foodcourt.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Set;

/**
 * Refresh-token, cookie and password-hashing settings, bound from {@code app.auth.*}.
 *
 * @param refreshTokenTtl rolling inactivity limit: a refresh token expires if not used within this time;
 *                        every successful refresh issues a successor valid for another full period.
 *                        There is no absolute session lifetime.
 */
@Validated
@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(@NotNull Duration refreshTokenTtl,
                             @Min(10) @Max(16) int bcryptStrength,
                             @NotNull @Valid Cookie cookie) {

    public AuthProperties {
        requirePositive(refreshTokenTtl, "app.auth.refresh-token-ttl");
    }

    private static void requirePositive(Duration value, String name) {
        if (value != null && (value.isNegative() || value.isZero())) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    /** Attributes of the HttpOnly refresh-token cookie. */
    public record Cookie(@NotBlank String name, @NotBlank String path, boolean secure, @NotBlank String sameSite) {

        private static final Set<String> SAME_SITE_VALUES = Set.of("Strict", "Lax", "None");

        public Cookie {
            if (sameSite != null && !SAME_SITE_VALUES.contains(sameSite)) {
                throw new IllegalArgumentException("app.auth.cookie.same-site must be one of " + SAME_SITE_VALUES);
            }
            if ("None".equals(sameSite) && !secure) {
                throw new IllegalArgumentException("app.auth.cookie.same-site=None requires app.auth.cookie.secure=true");
            }
        }
    }
}
