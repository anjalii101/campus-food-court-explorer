package com.campusfoodcourt.campus_foodcourt.auth;

import com.campusfoodcourt.campus_foodcourt.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the browser/SPA flow against the real embedded Tomcat and checks the Set-Cookie headers
 * exactly as they are written on the wire.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RealServerCookieHeaderTest extends AbstractIntegrationTest {

    private static final String CSRF_COOKIE = "__Host-XSRF-TOKEN";

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void spaCanObtainAndSubmitCsrfTokenAndCookiesAreHardenedOnTheWire() throws Exception {
        // 1. SPA fetches the CSRF token: host-only __Host- cookie + same token in the JSON body.
        HttpResponse<String> csrf = http.send(HttpRequest.newBuilder(uri("/api/auth/csrf")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        String csrfCookie = header(csrf.headers().allValues("Set-Cookie"), CSRF_COOKIE + "=");
        assertThat(csrfCookie).contains("Path=/", "Secure", "SameSite=Strict")
                .doesNotContain("HttpOnly").doesNotContainIgnoringCase("Domain=");
        String cookieToken = csrfCookie.substring(CSRF_COOKIE.length() + 1, csrfCookie.indexOf(';'));
        String bodyToken = json.readTree(csrf.body()).path("token").asText();
        assertThat(bodyToken).isEqualTo(cookieToken);
        assertThat(json.readTree(csrf.body()).path("headerName").asText()).isEqualTo("X-XSRF-TOKEN");

        // 2. SPA submits it: browser sends the cookie, SPA sets the header.
        String body = "{\"name\":\"Wire\",\"email\":\"wire-" + UUID.randomUUID()
                + "@campus.test\",\"password\":\"correct-horse-battery\"}";
        HttpResponse<String> register = http.send(HttpRequest.newBuilder(uri("/api/auth/register"))
                        .header("Content-Type", "application/json")
                        .header("Cookie", CSRF_COOKIE + "=" + cookieToken)
                        .header("X-XSRF-TOKEN", bodyToken)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(register.statusCode()).isEqualTo(201);
        assertThat(register.body()).doesNotContain("refreshToken");
        String issued = header(register.headers().allValues("Set-Cookie"), "refresh_token=");
        assertThat(issued).contains("Path=/api/auth", "Max-Age=604800", "Secure", "HttpOnly", "SameSite=Strict");
        String refreshToken = issued.substring("refresh_token=".length(), issued.indexOf(';'));

        // 3. Logout clears the refresh cookie with identical attributes.
        HttpResponse<String> logout = http.send(HttpRequest.newBuilder(uri("/api/auth/logout"))
                        .header("Cookie", CSRF_COOKIE + "=" + cookieToken + "; refresh_token=" + refreshToken)
                        .header("X-XSRF-TOKEN", cookieToken)
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(header(logout.headers().allValues("Set-Cookie"), "refresh_token="))
                .startsWith("refresh_token=;")
                .contains("Path=/api/auth", "Max-Age=0", "Secure", "HttpOnly", "SameSite=Strict");
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String header(List<String> setCookies, String prefix) {
        return setCookies.stream().filter(h -> h.startsWith(prefix)).findFirst()
                .orElseThrow(() -> new AssertionError("No Set-Cookie starting with " + prefix + " in " + setCookies));
    }
}
