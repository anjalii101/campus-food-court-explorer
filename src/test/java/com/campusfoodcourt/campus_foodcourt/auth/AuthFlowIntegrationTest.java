package com.campusfoodcourt.campus_foodcourt.auth;

import com.campusfoodcourt.campus_foodcourt.support.AbstractAuthApiTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Registration, login, access-token use and default-deny authorization over HTTP. */
class AuthFlowIntegrationTest extends AbstractAuthApiTest {

    // ---------------------------------------------------------------- registration

    @Test
    void registerReturnsAccessTokenInBodyAndRefreshTokenOnlyInHardenedCookie() throws Exception {
        String email = uniqueEmail();
        MvcResult result = register("Asha", email, PASSWORD)
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();

        JsonNode body = json(result);
        assertThat(body.path("token").asText()).isNotBlank();
        assertThat(body.path("tokenType").asText()).isEqualTo("Bearer");
        assertThat(body.path("expiresIn").asLong()).isEqualTo(900);
        assertThat(body.has("refreshToken")).as("refresh token must not be readable by scripts").isFalse();

        String cookie = setCookieHeader(result, REFRESH_COOKIE).orElseThrow();
        assertThat(cookie).contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/auth", "Max-Age=604800");
        assertThat(cookieValue(result, REFRESH_COOKIE)).hasSizeGreaterThanOrEqualTo(43); // 256 bits, base64url
    }

    @Test
    void registerStoresBcryptHashAndDefaultRole() throws Exception {
        String email = uniqueEmail();
        register("Asha", email, PASSWORD).andExpect(status().isCreated());

        String storedHash = jdbc.queryForObject("select password from users where email = ?", String.class, email);
        assertThat(storedHash).startsWith("$2a$10$").isNotEqualTo(PASSWORD);
        assertThat(jdbc.queryForObject("select role from users where email = ?", String.class, email))
                .isEqualTo("ROLE_USER");
    }

    @Test
    void registerIgnoresClientSuppliedRoleAndId() throws Exception {
        String email = uniqueEmail();
        mvc.perform(withCsrf(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"id": 999999, "name": "Mallory", "email": "%s", "password": "%s", "role": "ROLE_ADMIN"}
                        """.formatted(email, PASSWORD))))
                .andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("select role from users where email = ?", String.class, email))
                .isEqualTo("ROLE_USER");
        assertThat(jdbc.queryForObject("select count(*) from users where id = 999999", Integer.class)).isZero();
    }

    @Test
    void duplicateEmailIsRejectedCaseInsensitively() throws Exception {
        String email = uniqueEmail();
        register("A", email, PASSWORD).andExpect(status().isCreated());
        register("B", email.toUpperCase(), PASSWORD).andExpect(status().isConflict());
    }

    @Test
    void invalidRegistrationReturnsFieldErrors() throws Exception {
        register("", "not-an-email", "short")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.email").exists())
                .andExpect(jsonPath("$.errors.password").exists());
    }

    // ---------------------------------------------------------------- login

    @Test
    void loginSucceedsAndStartsANewSession() throws Exception {
        String email = uniqueEmail();
        Session registered = Session.of(email, register("Ravi", email, PASSWORD).andReturn(), objectMapper);

        MvcResult result = login(email.toUpperCase(), PASSWORD).andExpect(status().isOk()).andReturn();
        String loginRefresh = cookieValue(result, REFRESH_COOKIE);

        assertThat(json(result).path("token").asText()).isNotBlank();
        assertThat(loginRefresh).isNotEqualTo(registered.refreshToken());
        UUID registerFamily = familyOf(registered.refreshToken());
        assertThat(familyOf(loginRefresh)).isNotEqualTo(registerFamily);
    }

    @Test
    void loginFailuresAreIndistinguishable() throws Exception {
        String email = uniqueEmail();
        register("Ravi", email, PASSWORD);

        MvcResult wrongPassword = login(email, "wrong-password-123").andExpect(status().isUnauthorized()).andReturn();
        MvcResult unknownUser = login(uniqueEmail(), PASSWORD).andExpect(status().isUnauthorized()).andReturn();

        assertThat(wrongPassword.getResponse().getContentAsString())
                .isEqualTo(unknownUser.getResponse().getContentAsString());
        assertThat(setCookieHeader(wrongPassword, REFRESH_COOKIE)).isEmpty();
    }

    // ---------------------------------------------------------------- access token / deny by default

    @Test
    void protectedEndpointRequiresBearerToken() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
    }

    @Test
    void unmappedEndpointsAreDeniedByDefault() throws Exception {
        mvc.perform(get("/api/outlets")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/dishes")).andExpect(status().isUnauthorized());
    }

    @Test
    void healthProbeIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void validAccessTokenReturnsCurrentUserWithoutPassword() throws Exception {
        Session session = newSession();
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(session.email()))
                .andExpect(jsonPath("$.role").value("ROLE_USER"))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void refreshTokenIsNotAcceptedAsAccessToken() throws Exception {
        Session session = newSession();
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + session.refreshToken()))
                .andExpect(status().isUnauthorized());
    }

    private UUID familyOf(String rawRefreshToken) {
        return jdbc.queryForObject("select family_id from refresh_tokens where token_hash = ?", UUID.class,
                RefreshTokenService.hash(rawRefreshToken));
    }
}
