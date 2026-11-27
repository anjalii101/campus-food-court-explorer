package com.campusfoodcourt.campus_foodcourt.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AccountStatusException;
import org.springframework.security.authentication.AccountStatusUserDetailsChecker;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsChecker;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Authenticates requests carrying {@code Authorization: Bearer <access token>}.
 * Invalid, expired, wrongly issued/typed or unknown-user tokens, tokens of users who are not ACTIVE, and tokens
 * whose token version is no longer the user's current one (issued before a deactivation) leave the request
 * unauthenticated; the security chain's entry point then answers the same generic 401 for protected
 * endpoints, so the response does not reveal why the token was refused.
 * <p>
 * Deliberately not a Spring bean, so it is registered only inside the security chain.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtils jwtUtils;
    private final UserDetailsService userDetailsService;
    private final UserDetailsChecker accountStatusChecker = new AccountStatusUserDetailsChecker();

    public JwtAuthenticationFilter(JwtUtils jwtUtils, UserDetailsService userDetailsService) {
        this.jwtUtils = jwtUtils;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            authenticate(header.substring(BEARER_PREFIX.length()).trim(), request);
        }
        chain.doFilter(request, response);
    }

    private void authenticate(String token, HttpServletRequest request) {
        try {
            // One full verification (signature, HS256, expiry, issuer, token type, presence of a valid token-version
            // claim), then the user lookup.
            JwtUtils.VerifiedAccessToken verified = jwtUtils.verify(token);
            UserDetails user = userDetailsService.loadUserByUsername(verified.email());
            // Account status is read from the database on every request, so a suspended or deactivated user's
            // still-unexpired access token stops working immediately.
            accountStatusChecker.check(user);
            // The token must carry the user's current token version. Deactivation increments it, so a token issued
            // before a deactivation stays rejected after reactivation (a fresh login is required). Fail closed:
            // without a known version the token is refused.
            if (!(user instanceof AccountUserDetails account) || account.tokenVersion() != verified.tokenVersion()) {
                log.debug("Rejected bearer token: superseded token version");
                return;
            }
            UsernamePasswordAuthenticationToken authentication =
                    UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
        } catch (JwtException | IllegalArgumentException | UsernameNotFoundException | AccountStatusException e) {
            log.debug("Rejected bearer token: {}", e.getClass().getSimpleName());
        }
    }
}
