package com.campusfoodcourt.campus_foodcourt.auth.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * One independent {@link FixedWindowRateLimiter} per protected endpoint, keyed by client address, so that
 * exhausting one endpoint (or filling its table) never affects the others.
 */
@Component
public class AuthRateLimiter {

    public static final String LOGIN_PATH = "/api/auth/login";
    public static final String REGISTER_PATH = "/api/auth/register";
    public static final String REFRESH_PATH = "/api/auth/refresh";

    private record Limit(RequestMatcher matcher, FixedWindowRateLimiter limiter) {
    }

    private final List<Limit> limits;
    private final int ipv6PrefixLength;

    public AuthRateLimiter(AuthRateLimitProperties properties, Clock clock) {
        this.ipv6PrefixLength = properties.ipv6PrefixLength();
        PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
        this.limits = List.of(
                limit(paths, LOGIN_PATH, "login", properties.login(), properties.maxTrackedClients(), clock),
                limit(paths, REGISTER_PATH, "register", properties.register(), properties.maxTrackedClients(), clock),
                limit(paths, REFRESH_PATH, "refresh", properties.refresh(), properties.maxTrackedClients(), clock));
    }

    /** Counts the request if it targets a protected endpoint; empty for every other request. */
    public Optional<FixedWindowRateLimiter.Decision> check(HttpServletRequest request) {
        for (Limit limit : limits) {
            if (limit.matcher().matches(request)) {
                return Optional.of(limit.limiter().tryAcquire(
                        ClientAddresses.key(request.getRemoteAddr(), ipv6PrefixLength)));
            }
        }
        return Optional.empty();
    }

    private static Limit limit(PathPatternRequestMatcher.Builder paths, String path, String name,
                               AuthRateLimitProperties.Rule rule, int maxTrackedClients, Clock clock) {
        return new Limit(paths.matcher(HttpMethod.POST, path),
                new FixedWindowRateLimiter(name, rule.maxRequests(), rule.window(), maxTrackedClients, clock));
    }
}
