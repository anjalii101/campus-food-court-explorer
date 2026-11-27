package com.campusfoodcourt.campus_foodcourt.auth.ratelimit;

import com.campusfoodcourt.campus_foodcourt.security.ProblemResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Answers {@code 429 Too Many Requests} with {@code Retry-After} once a client exceeds the limit of a
 * protected auth endpoint. Runs inside the security chain before CSRF validation, so every attempt counts,
 * and a rejected request never reaches password checking, token rotation or the database.
 * <p>
 * Deliberately not a Spring bean, so it is registered only inside the security chain.
 */
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthRateLimitFilter.class);

    private final AuthRateLimiter rateLimiter;

    public AuthRateLimitFilter(AuthRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<FixedWindowRateLimiter.Decision> decision = rateLimiter.check(request);
        if (decision.isPresent() && !decision.get().allowed()) {
            long retryAfter = decision.get().retryAfterSeconds();
            log.debug("Rate limit exceeded for {} {}; Retry-After {}s", request.getMethod(),
                    request.getRequestURI(), retryAfter);
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            ProblemResponses.write(response, HttpStatus.TOO_MANY_REQUESTS,
                    "Too many requests. Try again later.");
            return;
        }
        chain.doFilter(request, response);
    }
}
