package com.campusfoodcourt.campus_foodcourt.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.util.WebUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Builds and reads the refresh-token cookie. Issuing and clearing share one builder, so name,
 * HttpOnly, Secure, SameSite and Path are always identical; only value and Max-Age differ.
 */
@Component
public class RefreshTokenCookies {

    private final AuthProperties.Cookie settings;
    private final Clock clock;

    public RefreshTokenCookies(AuthProperties authProperties, Clock clock) {
        this.settings = authProperties.cookie();
        this.clock = clock;
    }

    /** Cookie lifetime matches the token's own rolling inactivity expiry. */
    public ResponseCookie issue(String rawToken, Instant expiresAt) {
        long millis = Duration.between(clock.instant(), expiresAt).toMillis();
        long seconds = Math.max(0, (millis + 999) / 1000); // round up, never negative
        return base(rawToken).maxAge(Duration.ofSeconds(seconds)).build();
    }

    public ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    public Optional<String> read(HttpServletRequest request) {
        Cookie cookie = WebUtils.getCookie(request, settings.name());
        return Optional.ofNullable(cookie).map(Cookie::getValue).filter(v -> !v.isBlank());
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(settings.name(), value)
                .httpOnly(true)
                .secure(settings.secure())
                .sameSite(settings.sameSite())
                .path(settings.path());
    }
}
