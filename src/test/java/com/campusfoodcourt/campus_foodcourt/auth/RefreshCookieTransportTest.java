package com.campusfoodcourt.campus_foodcourt.auth;

import com.campusfoodcourt.campus_foodcourt.support.AbstractAuthApiTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The refresh token travels exclusively in the HttpOnly cookie: never in a JSON body (request or response),
 * and every Set-Cookie for it (issue, rotate, clear) carries the same security attributes.
 */
class RefreshCookieTransportTest extends AbstractAuthApiTest {

    @Test
    void refreshTokenInJsonBodyIsIgnored() throws Exception {
        Session session = newSession();

        mvc.perform(withCsrf(jsonPost("/api/auth/refresh", Map.of("refreshToken", session.refreshToken()))))
                .andExpect(status().isUnauthorized());
        mvc.perform(withCsrf(jsonPost("/api/auth/logout", Map.of("refreshToken", session.refreshToken()))))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select revoked_at from refresh_tokens where token_hash = ?", Instant.class,
                RefreshTokenService.hash(session.refreshToken()))).as("body token must not rotate or revoke").isNull();
        refresh(session.refreshToken()).andExpect(status().isOk());
    }

    @Test
    void noResponseBodyEverContainsTheRefreshToken() throws Exception {
        String email = uniqueEmail();
        MvcResult registered = register("Body", email, PASSWORD).andReturn();
        MvcResult loggedIn = login(email, PASSWORD).andReturn();
        String token = cookieValue(loggedIn, REFRESH_COOKIE);
        MvcResult refreshed = refresh(token).andReturn();
        MvcResult replayError = refresh(token).andExpect(status().isUnauthorized()).andReturn();
        MvcResult loginError = login(email, "wrong-password-123").andReturn();

        for (MvcResult r : List.of(registered, loggedIn, refreshed, replayError, loginError)) {
            String body = r.getResponse().getContentAsString();
            assertThat(body).doesNotContain("refreshToken").doesNotContain(token)
                    .doesNotContain(cookieValueIfPresent(r));
        }
    }

    @Test
    void issueRotateAndClearCookiesShareIdenticalSecurityAttributes() throws Exception {
        Session session = newSession();
        MvcResult loginResult = login(session.email(), PASSWORD).andReturn();
        MvcResult rotated = refresh(cookieValue(loginResult, REFRESH_COOKIE)).andExpect(status().isOk()).andReturn();
        MvcResult loggedOut = logout(cookieValue(rotated, REFRESH_COOKIE)).andExpect(status().isNoContent()).andReturn();
        MvcResult rejected = refresh("unknown-token").andExpect(status().isUnauthorized()).andReturn();

        List<String> headers = new ArrayList<>();
        for (MvcResult r : List.of(loginResult, rotated, loggedOut, rejected)) {
            headers.add(setCookieHeader(r, REFRESH_COOKIE).orElseThrow());
        }

        TreeSet<String> expected = new TreeSet<>(List.of("HttpOnly", "Path=/api/auth", "SameSite=Strict", "Secure"));
        for (String header : headers) {
            assertThat(securityAttributes(header)).as(header).isEqualTo(expected);
        }
        assertThat(headers.get(0)).contains("Max-Age=604800");
        assertThat(headers.get(1)).contains("Max-Age=604800");
        assertThat(headers.get(2)).startsWith(REFRESH_COOKIE + "=;").contains("Max-Age=0");
        assertThat(headers.get(3)).startsWith(REFRESH_COOKIE + "=;").contains("Max-Age=0");
    }

    @Test
    void concurrentRegistrationWithSameEmailYieldsOneCreatedAndOneConflict() throws Exception {
        String email = uniqueEmail();
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> statuses = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            var request = withCsrf(jsonPost("/api/auth/register",
                    Map.of("name", "Race", "email", email, "password", PASSWORD)));
            statuses.add(pool.submit(() -> {
                start.await();
                return mvc.perform(request).andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        List<Integer> codes = new ArrayList<>();
        for (Future<Integer> f : statuses) {
            codes.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(codes).containsOnly(201, 409);
        assertThat(codes.stream().filter(c -> c == 201).count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email)).isOne();
    }

    /** Attributes that must never differ between issuing and clearing (everything except value/expiry). */
    private static TreeSet<String> securityAttributes(String setCookieHeader) {
        return Arrays.stream(setCookieHeader.split(";"))
                .skip(1)
                .map(String::trim)
                .filter(a -> !a.startsWith("Max-Age") && !a.startsWith("Expires"))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static String cookieValueIfPresent(MvcResult result) {
        return setCookieHeader(result, REFRESH_COOKIE)
                .map(h -> h.substring(REFRESH_COOKIE.length() + 1, h.indexOf(';')))
                .filter(v -> !v.isEmpty())
                .orElse("\u0000no-cookie\u0000");
    }
}
