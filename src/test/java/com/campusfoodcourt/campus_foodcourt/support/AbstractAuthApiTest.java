package com.campusfoodcourt.campus_foodcourt.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** HTTP-level helpers that behave like a browser SPA: fetch a CSRF token, carry the refresh cookie. */
@AutoConfigureMockMvc
public abstract class AbstractAuthApiTest extends AbstractIntegrationTest {

    protected static final String PASSWORD = "correct-horse-battery";
    protected static final String REFRESH_COOKIE = "refresh_token";
    protected static final String CSRF_COOKIE = "__Host-XSRF-TOKEN";
    protected static final String CSRF_HEADER = "X-XSRF-TOKEN";

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected JdbcTemplate jdbc;

    protected static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@campus.test";
    }

    /** Obtains a CSRF token the way an SPA does: GET /api/auth/csrf. */
    protected String fetchCsrfToken() throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/csrf")).andReturn();
        return json(result).path("token").asText();
    }

    protected MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) throws Exception {
        String token = fetchCsrfToken();
        return request.cookie(new Cookie(CSRF_COOKIE, token)).header(CSRF_HEADER, token);
    }

    protected MockHttpServletRequestBuilder jsonPost(String url, Object body) throws Exception {
        return post(url).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
    }

    protected ResultActions register(String name, String email, String password) throws Exception {
        return mvc.perform(withCsrf(jsonPost("/api/auth/register",
                Map.of("name", name, "email", email, "password", password))));
    }

    protected ResultActions login(String email, String password) throws Exception {
        return mvc.perform(withCsrf(jsonPost("/api/auth/login", Map.of("email", email, "password", password))));
    }

    protected ResultActions refresh(String refreshToken) throws Exception {
        return mvc.perform(withCsrf(post("/api/auth/refresh")).cookie(new Cookie(REFRESH_COOKIE, refreshToken)));
    }

    protected ResultActions logout(String refreshToken) throws Exception {
        return mvc.perform(withCsrf(post("/api/auth/logout")).cookie(new Cookie(REFRESH_COOKIE, refreshToken)));
    }

    /** Registers a fresh user and returns the session (access token + refresh cookie value). */
    protected Session newSession() throws Exception {
        String email = uniqueEmail();
        return Session.of(email, register("Test User", email, PASSWORD).andReturn(), objectMapper);
    }

    protected JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    protected static Optional<String> setCookieHeader(MvcResult result, String cookieName) {
        return result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(h -> h.startsWith(cookieName + "="))
                .findFirst();
    }

    protected static String cookieValue(MvcResult result, String cookieName) {
        String header = setCookieHeader(result, cookieName)
                .orElseThrow(() -> new AssertionError("No Set-Cookie for " + cookieName));
        int end = header.indexOf(';');
        return header.substring(cookieName.length() + 1, end < 0 ? header.length() : end);
    }

    protected record Session(String email, String accessToken, String refreshToken) {
        public static Session of(String email, MvcResult result, ObjectMapper mapper) throws Exception {
            String access = mapper.readTree(result.getResponse().getContentAsString()).path("token").asText();
            return new Session(email, access, cookieValue(result, REFRESH_COOKIE));
        }
    }
}
