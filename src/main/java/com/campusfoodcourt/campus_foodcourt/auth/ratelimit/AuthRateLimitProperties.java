package com.campusfoodcourt.campus_foodcourt.auth.ratelimit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Per-client limits for the unauthenticated auth endpoints, bound from {@code app.auth.rate-limit.*}.
 * Defaults live in application.properties and are documented in docs/authentication.md.
 *
 * @param maxTrackedClients hard cap on the number of distinct clients tracked <b>per endpoint</b>; bounds memory
 * @param ipv6PrefixLength  IPv6 clients are grouped by this prefix (64 = one subscriber/LAN), because a single
 *                          user can trivially rotate through the addresses of their own /64
 * @param login             limit for {@code POST /api/auth/login}
 * @param register          limit for {@code POST /api/auth/register}
 * @param refresh           limit for {@code POST /api/auth/refresh}
 */
@Validated
@ConfigurationProperties(prefix = "app.auth.rate-limit")
public record AuthRateLimitProperties(@Min(1) int maxTrackedClients,
                                      @Min(32) @Max(128) int ipv6PrefixLength,
                                      @NotNull @Valid Rule login,
                                      @NotNull @Valid Rule register,
                                      @NotNull @Valid Rule refresh) {

    /** At most {@code maxRequests} requests per client within each {@code window}. */
    public record Rule(@Min(1) int maxRequests, @NotNull Duration window) {

        public Rule {
            if (window != null && (window.isNegative() || window.isZero())) {
                throw new IllegalArgumentException("app.auth.rate-limit.*.window must be positive");
            }
        }
    }
}
