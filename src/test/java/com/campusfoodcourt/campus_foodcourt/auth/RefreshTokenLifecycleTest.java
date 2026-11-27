package com.campusfoodcourt.campus_foodcourt.auth;

import com.campusfoodcourt.campus_foodcourt.support.AbstractAuthApiTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Refresh-token rotation, reuse detection, revocation, expiry and logout over HTTP. */
class RefreshTokenLifecycleTest extends AbstractAuthApiTest {

    // ---------------------------------------------------------------- rotation

    @Test
    void refreshRotatesTokenAndIssuesWorkingAccessToken() throws Exception {
        Session session = newSession();
        MvcResult result = refresh(session.refreshToken()).andExpect(status().isOk()).andReturn();

        String next = cookieValue(result, REFRESH_COOKIE);
        assertThat(next).isNotEqualTo(session.refreshToken());
        assertThat(setCookieHeader(result, REFRESH_COOKIE).orElseThrow())
                .contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/auth");
        assertThat(json(result).has("refreshToken")).isFalse();

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + json(result).path("token").asText()))
                .andExpect(status().isOk());

        // Old row is revoked and linked to its successor; successor is active, same family, fresh 7-day expiry.
        assertThat(row(session.refreshToken(), "revoked_at", Instant.class)).isNotNull();
        assertThat(row(session.refreshToken(), "replaced_by_id", Long.class)).isEqualTo(row(next, "id", Long.class));
        assertThat(row(next, "revoked_at", Instant.class)).isNull();
        assertThat(row(next, "family_id", UUID.class)).isEqualTo(row(session.refreshToken(), "family_id", UUID.class));
        assertThat(row(next, "expires_at", Instant.class))
                .isCloseTo(Instant.now().plus(Duration.ofDays(7)), within(Duration.ofMinutes(1)));
    }

    @Test
    void tokenChainCanBeRotatedRepeatedly() throws Exception {
        String token = newSession().refreshToken();
        for (int i = 0; i < 5; i++) {
            token = cookieValue(refresh(token).andExpect(status().isOk()).andReturn(), REFRESH_COOKIE);
        }
        assertThat(row(token, "revoked_at", Instant.class)).isNull();
    }

    // ---------------------------------------------------------------- reuse detection

    @Test
    void reusingRotatedTokenRevokesWholeFamilyAndClearsCookie() throws Exception {
        String t1 = newSession().refreshToken();
        String t2 = cookieValue(refresh(t1).andExpect(status().isOk()).andReturn(), REFRESH_COOKIE);
        String t3 = cookieValue(refresh(t2).andExpect(status().isOk()).andReturn(), REFRESH_COOKIE);

        MvcResult replay = refresh(t1).andExpect(status().isUnauthorized()).andReturn();
        assertThat(setCookieHeader(replay, REFRESH_COOKIE).orElseThrow()).contains("Max-Age=0");

        refresh(t3).andExpect(status().isUnauthorized()); // the legitimate latest token is now dead too
        UUID family = row(t1, "family_id", UUID.class);
        assertThat(jdbc.queryForObject(
                "select count(*) from refresh_tokens where family_id = ? and revoked_at is null", Integer.class, family))
                .isZero();
    }

    @Test
    void reuseInOneSessionDoesNotAffectOtherSessionsOfSameUser() throws Exception {
        Session first = newSession();
        String other = cookieValue(login(first.email(), PASSWORD).andExpect(status().isOk()).andReturn(),
                REFRESH_COOKIE);

        refresh(first.refreshToken()).andExpect(status().isOk());
        refresh(first.refreshToken()).andExpect(status().isUnauthorized()); // reuse -> first family revoked

        refresh(other).andExpect(status().isOk()); // separate login family unaffected
    }

    // ---------------------------------------------------------------- expiry / unknown / missing

    @Test
    void expiredRefreshTokenIsRejectedAndCookieCleared() throws Exception {
        String token = newSession().refreshToken();
        jdbc.update("update refresh_tokens set expires_at = now() - interval '1 second' where token_hash = ?",
                RefreshTokenService.hash(token));

        MvcResult result = refresh(token).andExpect(status().isUnauthorized()).andReturn();
        assertThat(setCookieHeader(result, REFRESH_COOKIE).orElseThrow()).contains("Max-Age=0");
    }

    @Test
    void unknownRefreshTokenIsRejected() throws Exception {
        refresh("not-a-real-token").andExpect(status().isUnauthorized());
    }

    @Test
    void missingRefreshCookieIsRejected() throws Exception {
        mvc.perform(withCsrf(post("/api/auth/refresh"))).andExpect(status().isUnauthorized());
    }

    @Test
    void refreshTokensAreStoredOnlyAsHashes() throws Exception {
        String token = newSession().refreshToken();
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where token_hash = ?", Integer.class,
                token)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where token_hash = ?", Integer.class,
                RefreshTokenService.hash(token))).isOne();
    }

    // ---------------------------------------------------------------- logout / revocation

    @Test
    void logoutRevokesSessionAndClearsCookie() throws Exception {
        String t1 = newSession().refreshToken();
        String t2 = cookieValue(refresh(t1).andExpect(status().isOk()).andReturn(), REFRESH_COOKIE);

        MvcResult result = logout(t2).andExpect(status().isNoContent()).andReturn();
        assertThat(setCookieHeader(result, REFRESH_COOKIE).orElseThrow()).contains("Max-Age=0", "Path=/api/auth");

        refresh(t2).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject(
                "select count(*) from refresh_tokens where family_id = ? and revoked_at is null", Integer.class,
                row(t1, "family_id", UUID.class))).isZero();
    }

    @Test
    void logoutWithoutCookieOrWithUnknownTokenStillSucceeds() throws Exception {
        mvc.perform(withCsrf(post("/api/auth/logout"))).andExpect(status().isNoContent());
        logout("unknown-token").andExpect(status().isNoContent());
    }

    @Test
    void logoutDoesNotRevokeOtherSessions() throws Exception {
        Session first = newSession();
        String other = cookieValue(login(first.email(), PASSWORD).andReturn(), REFRESH_COOKIE);

        logout(first.refreshToken()).andExpect(status().isNoContent());
        refresh(other).andExpect(status().isOk());
    }

    private <T> T row(String rawToken, String column, Class<T> type) {
        return jdbc.queryForObject("select " + column + " from refresh_tokens where token_hash = ?", type,
                RefreshTokenService.hash(rawToken));
    }
}
