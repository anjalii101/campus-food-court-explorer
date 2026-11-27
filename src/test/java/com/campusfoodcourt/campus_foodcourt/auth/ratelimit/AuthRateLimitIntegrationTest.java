package com.campusfoodcourt.campus_foodcourt.auth.ratelimit;

import com.campusfoodcourt.campus_foodcourt.support.AbstractAuthApiTest;
import com.campusfoodcourt.campus_foodcourt.support.MutableClock;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Rate limiting through the real security filter chain, with small limits and a controllable clock.
 * Every test uses its own client addresses, so tests do not share budgets.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "app.auth.rate-limit.login.max-requests=3",
        "app.auth.rate-limit.login.window=PT1M",
        "app.auth.rate-limit.register.max-requests=2",
        "app.auth.rate-limit.register.window=PT10M",
        "app.auth.rate-limit.refresh.max-requests=3",
        "app.auth.rate-limit.refresh.window=PT1M"
})
class AuthRateLimitIntegrationTest extends AbstractAuthApiTest {

    private static final MutableClock CLOCK = new MutableClock(Instant.now());
    private static final AtomicInteger NEXT_CLIENT = new AtomicInteger(1);

    @TestBean
    private Clock clock;

    static Clock clock() {
        return CLOCK;
    }

    @LocalServerPort
    private int port;

    // ------------------------------------------------------------------ allowed / blocked

    @Test
    void loginRequestsWithinTheLimitAreProcessedNormally() throws Exception {
        String email = registerUser();
        String client = newClient();
        for (int i = 0; i < 3; i++) {
            login(client, email, PASSWORD).andExpect(status().isOk());
        }
    }

    @Test
    void loginOverTheLimitIs429WithRetryAfterAndNoSession() throws Exception {
        String email = registerUser();
        String client = newClient();
        for (int i = 0; i < 3; i++) {
            login(client, email, PASSWORD).andExpect(status().isOk());
        }

        MvcResult blocked = login(client, email, PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.title").value("Too Many Requests"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn();

        long retryAfter = Long.parseLong(blocked.getResponse().getHeader(HttpHeaders.RETRY_AFTER));
        assertThat(retryAfter).isBetween(1L, 60L);
        assertThat(blocked.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                .noneMatch(c -> c.startsWith(REFRESH_COOKIE + "="));
    }

    @Test
    void failedAndCsrfRejectedAttemptsCountTowardsTheLimit() throws Exception {
        String email = registerUser();
        String client = newClient();
        login(client, email, "wrong-password-1").andExpect(status().isUnauthorized());
        login(client, email, "wrong-password-2").andExpect(status().isUnauthorized());
        mvc.perform(from(client, jsonPost("/api/auth/login", Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isForbidden()); // no CSRF token

        login(client, email, PASSWORD).andExpect(status().isTooManyRequests()); // even the correct password
    }

    @Test
    void registerOverTheLimitIs429AndCreatesNoUser() throws Exception {
        String client = newClient();
        register(client, uniqueEmail()).andExpect(status().isCreated());
        register(client, uniqueEmail()).andExpect(status().isCreated());

        String email = uniqueEmail();
        MvcResult blocked = register(client, email).andExpect(status().isTooManyRequests()).andReturn();
        assertThat(Long.parseLong(blocked.getResponse().getHeader(HttpHeaders.RETRY_AFTER))).isBetween(1L, 600L);
        assertThat(jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email)).isZero();
    }

    @Test
    void refreshOverTheLimitIs429AndDoesNotRotateTheToken() throws Exception {
        String client = newClient();
        String token = refreshTokenOf(register(client, uniqueEmail()).andExpect(status().isCreated()).andReturn());
        for (int i = 0; i < 3; i++) {
            token = refreshTokenOf(refresh(client, token).andExpect(status().isOk()).andReturn());
        }

        refresh(client, token).andExpect(status().isTooManyRequests());
        assertThat(jdbc.queryForObject("select revoked_at from refresh_tokens where token_hash = ?",
                Instant.class, sha256Hex(token))).as("throttled request must not touch the token").isNull();

        CLOCK.advance(Duration.ofMinutes(1));
        refresh(client, token).andExpect(status().isOk()); // the same token still works after the window
    }

    // ------------------------------------------------------------------ isolation between clients/endpoints

    @Test
    void limitsArePerClientAddress() throws Exception {
        String email = registerUser();
        String client = newClient();
        for (int i = 0; i < 3; i++) {
            login(client, email, PASSWORD).andExpect(status().isOk());
        }
        login(client, email, PASSWORD).andExpect(status().isTooManyRequests());
        login(newClient(), email, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void limitsArePerEndpoint() throws Exception {
        String email = registerUser();
        String client = newClient();
        for (int i = 0; i < 3; i++) {
            login(client, email, PASSWORD).andExpect(status().isOk());
        }
        login(client, email, PASSWORD).andExpect(status().isTooManyRequests());

        register(client, uniqueEmail()).andExpect(status().isCreated());
        String token = refreshTokenOf(login(newClient(), email, PASSWORD).andReturn());
        refresh(client, token).andExpect(status().isOk());
    }

    @Test
    void unprotectedEndpointsAreNotLimited() throws Exception {
        String client = newClient();
        for (int i = 0; i < 20; i++) {
            mvc.perform(get("/api/auth/csrf").with(remoteAddr(client))).andExpect(status().isOk());
            mvc.perform(get("/actuator/health").with(remoteAddr(client))).andExpect(status().isOk());
        }
        for (int i = 0; i < 5; i++) {
            mvc.perform(withCsrf(post("/api/auth/logout")).with(remoteAddr(client))).andExpect(status().isNoContent());
        }
    }

    @Test
    void ipv6ClientsAreLimitedPerSlash64() throws Exception {
        String email = registerUser();
        int n = NEXT_CLIENT.incrementAndGet();
        for (int i = 1; i <= 3; i++) {
            login("2001:db8:" + n + ":1::" + i, email, PASSWORD).andExpect(status().isOk());
        }
        // A different address from the same /64 shares the budget ...
        login("2001:db8:" + n + ":1:ffff:ffff:ffff:ffff", email, PASSWORD).andExpect(status().isTooManyRequests());
        // ... a different /64 does not.
        login("2001:db8:" + n + ":2::1", email, PASSWORD).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ reset

    @Test
    void limitResetsAfterTheConfiguredWindow() throws Exception {
        String email = registerUser();
        String client = newClient();
        for (int i = 0; i < 3; i++) {
            login(client, email, PASSWORD).andExpect(status().isOk());
        }
        login(client, email, PASSWORD).andExpect(status().isTooManyRequests());

        CLOCK.advance(Duration.ofSeconds(59));
        login(client, email, PASSWORD).andExpect(status().isTooManyRequests());

        CLOCK.advance(Duration.ofSeconds(1));
        for (int i = 0; i < 3; i++) {
            login(client, email, PASSWORD).andExpect(status().isOk());
        }
        login(client, email, PASSWORD).andExpect(status().isTooManyRequests());
    }

    // ------------------------------------------------------------------ real server: proxy headers

    @Test
    void forwardedHeadersCannotBeUsedToEscapeTheLimitOnTheRealServer() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        // All requests come from the same TCP peer (loopback) but claim different client addresses.
        HttpResponse<String> csrf = http.send(HttpRequest.newBuilder(uri("/api/auth/csrf")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        String token = objectMapper.readTree(csrf.body()).path("token").asText();

        int[] statuses = new int[4];
        for (int i = 0; i < 4; i++) {
            HttpRequest login = HttpRequest.newBuilder(uri("/api/auth/login"))
                    .header("Content-Type", "application/json")
                    .header("Cookie", CSRF_COOKIE + "=" + token)
                    .header(CSRF_HEADER, token)
                    .header("X-Forwarded-For", "198.51.100." + (i + 1))
                    .header("X-Real-IP", "198.51.100." + (i + 1))
                    .header("Forwarded", "for=198.51.100." + (i + 1))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"email\":\"nobody@campus.test\",\"password\":\"wrong-password\"}"))
                    .build();
            HttpResponse<String> response = http.send(login, HttpResponse.BodyHandlers.ofString());
            statuses[i] = response.statusCode();
            if (i == 3) {
                assertThat(response.headers().firstValue("Retry-After")).isPresent();
            }
        }
        assertThat(statuses).containsExactly(401, 401, 401, 429);
    }

    // ------------------------------------------------------------------ helpers

    private static String newClient() {
        int n = NEXT_CLIENT.incrementAndGet();
        return "10." + (n >> 16 & 0xFF) + "." + (n >> 8 & 0xFF) + "." + (n & 0xFF);
    }

    private static RequestPostProcessor remoteAddr(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private MockHttpServletRequestBuilder from(String client, MockHttpServletRequestBuilder request) {
        return request.with(remoteAddr(client));
    }

    /** Registers a user from a throwaway client address and returns the email. */
    private String registerUser() throws Exception {
        String email = uniqueEmail();
        register(newClient(), email).andExpect(status().isCreated());
        return email;
    }

    private ResultActions register(String client, String email) throws Exception {
        return mvc.perform(from(client, withCsrf(jsonPost("/api/auth/register",
                Map.of("name", "Limited", "email", email, "password", PASSWORD)))));
    }

    private ResultActions login(String client, String email, String password) throws Exception {
        return mvc.perform(from(client, withCsrf(jsonPost("/api/auth/login",
                Map.of("email", email, "password", password)))));
    }

    private ResultActions refresh(String client, String refreshToken) throws Exception {
        return mvc.perform(from(client, withCsrf(post("/api/auth/refresh")))
                .cookie(new Cookie(REFRESH_COOKIE, refreshToken)));
    }

    /** Same hashing as RefreshTokenService (SHA-256, lower-case hex), which is package-private there. */
    private static String sha256Hex(String raw) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static String refreshTokenOf(MvcResult result) {
        return cookieValue(result, REFRESH_COOKIE);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
