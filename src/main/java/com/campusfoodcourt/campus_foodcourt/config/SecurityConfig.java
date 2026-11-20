package com.campusfoodcourt.campus_foodcourt.config;

import com.campusfoodcourt.campus_foodcourt.auth.AuthProperties;
import com.campusfoodcourt.campus_foodcourt.security.JwtAuthenticationFilter;
import com.campusfoodcourt.campus_foodcourt.security.JwtUtils;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.io.IOException;

/**
 * Stateless security, deny by default.
 *
 * <h2>CSRF</h2>
 * CSRF is a risk only where the browser attaches a credential automatically, i.e. a cookie.
 * <ul>
 *   <li>The refresh token is an HttpOnly cookie, so every endpoint that reads it (refresh, logout) or
 *       sets it (register, login) <b>requires a CSRF token</b>: a double-submit token kept in the
 *       readable, host-only {@code __Host-XSRF-TOKEN} cookie that must be echoed in the {@code X-XSRF-TOKEN} header.
 *       A cross-site page can make the browser send cookies but cannot read them to forge the header.
 *       Protecting login/register also prevents login-CSRF (forcing a victim into an attacker's account).</li>
 *   <li>All other endpoints authenticate only via the {@code Authorization: Bearer} header, which browsers
 *       never attach on their own, so a CSRF token adds nothing there and is not required.</li>
 *   <li>The refresh cookie is additionally {@code SameSite=Strict} and path-scoped to {@code /api/auth}
 *       as defence in depth.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    public static final String CSRF_COOKIE_NAME = "__Host-XSRF-TOKEN";

    static final String[] COOKIE_AUTH_ENDPOINTS = {
            "/api/auth/register", "/api/auth/login", "/api/auth/refresh", "/api/auth/logout"
    };

    @Bean
    public PasswordEncoder passwordEncoder(AuthProperties authProperties) {
        return new BCryptPasswordEncoder(authProperties.bcryptStrength());
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtUtils jwtUtils,
                                                   UserDetailsService userDetailsService,
                                                   AuthProperties authProperties) throws Exception {
        AuthProperties.Cookie cookie = authProperties.cookie();

        // __Host- prefix: browsers only accept it with Secure, Path=/ and no Domain, so a sibling subdomain
        // cannot plant or overwrite it (defeats cookie tossing against the double-submit check).
        CookieCsrfTokenRepository csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokenRepository.setCookieName(CSRF_COOKIE_NAME);
        csrfTokenRepository.setCookieCustomizer(c -> c.secure(true).path("/").domain(null).sameSite(cookie.sameSite()));

        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        // Plain (non-XOR) handler: the header value equals the cookie value, as SPAs send it.
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .requireCsrfProtectionMatcher(cookieAuthEndpoints()))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, COOKIE_AUTH_ENDPOINTS).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) -> {
                            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                            writeProblem(response, HttpStatus.UNAUTHORIZED, "Authentication is required");
                        })
                        .accessDeniedHandler((request, response, e) -> writeProblem(response, HttpStatus.FORBIDDEN,
                                e instanceof CsrfException ? "Missing or invalid CSRF token" : "Access is denied")))
                .addFilterBefore(new JwtAuthenticationFilter(jwtUtils, userDetailsService),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static RequestMatcher cookieAuthEndpoints() {
        PathPatternRequestMatcher.Builder post = PathPatternRequestMatcher.withDefaults();
        RequestMatcher[] matchers = new RequestMatcher[COOKIE_AUTH_ENDPOINTS.length];
        for (int i = 0; i < COOKIE_AUTH_ENDPOINTS.length; i++) {
            matchers[i] = post.matcher(HttpMethod.POST, COOKIE_AUTH_ENDPOINTS[i]);
        }
        return new OrRequestMatcher(matchers);
    }

    private static void writeProblem(HttpServletResponse response, HttpStatus status, String detail)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + status.getReasonPhrase()
                + "\",\"status\":" + status.value() + ",\"detail\":\"" + detail + "\"}");
    }
}
