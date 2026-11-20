package com.campusfoodcourt.campus_foodcourt.auth;

import com.campusfoodcourt.campus_foodcourt.support.AbstractAuthApiTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Cookie-authenticated endpoints require a double-submit CSRF token; bearer-token endpoints do not. */
class CsrfProtectionTest extends AbstractAuthApiTest {

    @Test
    void csrfEndpointIssuesReadableCookieMatchingBody() throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk())
                .andExpect(jsonPath("$.headerName").value(CSRF_HEADER))
                .andReturn();

        // Inspect the servlet Cookie the repository wrote: MockHttpServletResponse does not render custom
        // Servlet 6 cookie attributes (SameSite) into its Set-Cookie header string, whereas Tomcat does.
        Cookie cookie = result.getResponse().getCookie("__Host-XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).as("SPA must be able to read the CSRF cookie").isFalse();
        // __Host- prefix requirements: Secure, Path=/, no Domain (host-only)
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getDomain()).isNull();
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Strict");
        assertThat(cookie.getValue()).isEqualTo(json(result).path("token").asText());
    }

    @Test
    void refreshWithCookieButWithoutCsrfTokenIsForbiddenAndDoesNotRotate() throws Exception {
        Session session = newSession();

        mvc.perform(post("/api/auth/refresh").cookie(new Cookie(REFRESH_COOKIE, session.refreshToken())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Missing or invalid CSRF token"));

        assertThat(revokedAt(session.refreshToken())).isNull();
        refresh(session.refreshToken()).andExpect(status().isOk()); // still valid with a proper CSRF token
    }

    @Test
    void legacyUnprefixedCsrfCookieIsNotAccepted() throws Exception {
        // A cookie planted under the old/unprefixed name (e.g. by a sibling subdomain) must not satisfy the check.
        Session session = newSession();
        mvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie(REFRESH_COOKIE, session.refreshToken()), new Cookie("XSRF-TOKEN", "planted"))
                        .header(CSRF_HEADER, "planted"))
                .andExpect(status().isForbidden());
        assertThat(revokedAt(session.refreshToken())).isNull();
    }

    @Test
    void refreshWithMismatchedCsrfHeaderIsForbidden() throws Exception {
        Session session = newSession();
        mvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie(REFRESH_COOKIE, session.refreshToken()),
                                new Cookie(CSRF_COOKIE, fetchCsrfToken()))
                        .header(CSRF_HEADER, "attacker-guess"))
                .andExpect(status().isForbidden());
        assertThat(revokedAt(session.refreshToken())).isNull();
    }

    @Test
    void logoutWithoutCsrfTokenIsForbiddenAndDoesNotRevoke() throws Exception {
        Session session = newSession();
        mvc.perform(post("/api/auth/logout").cookie(new Cookie(REFRESH_COOKIE, session.refreshToken())))
                .andExpect(status().isForbidden());
        assertThat(revokedAt(session.refreshToken())).isNull();
    }

    @Test
    void loginAndRegisterRequireCsrfToken() throws Exception {
        String email = uniqueEmail();
        mvc.perform(jsonPost("/api/auth/register", Map.of("name", "X", "email", email, "password", PASSWORD)))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email)).isZero();

        register("X", email, PASSWORD).andExpect(status().isCreated());
        mvc.perform(jsonPost("/api/auth/login", Map.of("email", email, "password", PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void bearerAuthenticatedRequestsDoNotNeedCsrfToken() throws Exception {
        Session session = newSession();
        // No such handler exists: reaching 404 proves the request passed both CSRF and authentication.
        mvc.perform(post("/api/some-bearer-endpoint").header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isNotFound());
    }

    private Instant revokedAt(String rawToken) {
        return jdbc.queryForObject("select revoked_at from refresh_tokens where token_hash = ?", Instant.class,
                RefreshTokenService.hash(rawToken));
    }
}
