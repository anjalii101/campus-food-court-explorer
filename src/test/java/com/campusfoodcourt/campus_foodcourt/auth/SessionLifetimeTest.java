package com.campusfoodcourt.campus_foodcourt.auth;

import com.campusfoodcourt.campus_foodcourt.support.AbstractAuthApiTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Session policy: a 7-day rolling inactivity expiry, renewed by every successful refresh, and no absolute
 * session lifetime. (Reuse detection and logout revocation are covered by RefreshTokenLifecycleTest and
 * RevocationReasonTest.)
 */
class SessionLifetimeTest extends AbstractAuthApiTest {

    private static final Duration SEVEN_DAYS = Duration.ofDays(7);

    @Autowired
    private AuthProperties authProperties;
    @Autowired
    private RefreshTokenService refreshTokenService;

    @Test
    void defaultIsASevenDayRollingInactivityExpiry() {
        assertThat(authProperties.refreshTokenTtl()).isEqualTo(SEVEN_DAYS);
    }

    @Test
    void initialRefreshTokenExpiresSevenDaysAfterIssue() throws Exception {
        MvcResult registered = register("Seven", uniqueEmail(), PASSWORD).andReturn();
        String token = cookieValue(registered, REFRESH_COOKIE);

        assertThat(Duration.between(col(token, "created_at"), col(token, "expires_at"))).isEqualTo(SEVEN_DAYS);
        assertThat(col(token, "expires_at")).isCloseTo(Instant.now().plus(SEVEN_DAYS), within(Duration.ofMinutes(1)));
        assertThat(setCookieHeader(registered, REFRESH_COOKIE).orElseThrow()).contains("Max-Age=604800");
    }

    @Test
    void successfulRefreshExtendsExpiryByAnotherSevenDays() throws Exception {
        String token = newSession().refreshToken();
        // Simulate 6 days of inactivity: only 1 day of validity left on the current token.
        jdbc.update("update refresh_tokens set created_at = now() - interval '6 days', "
                + "expires_at = now() + interval '1 day' where token_hash = ?", RefreshTokenService.hash(token));
        Instant oldExpiry = col(token, "expires_at");

        MvcResult result = refresh(token).andExpect(status().isOk()).andReturn();
        String next = cookieValue(result, REFRESH_COOKIE);

        assertThat(col(next, "expires_at"))
                .isCloseTo(Instant.now().plus(SEVEN_DAYS), within(Duration.ofMinutes(1)))
                .isAfter(oldExpiry.plus(Duration.ofDays(5)));
        assertThat(Duration.between(col(next, "created_at"), col(next, "expires_at"))).isEqualTo(SEVEN_DAYS);
        assertThat(setCookieHeader(result, REFRESH_COOKIE).orElseThrow()).contains("Max-Age=604800");
    }

    @Test
    void repeatedRefreshesHitNoAbsoluteSessionCap() throws Exception {
        String token = newSession().refreshToken();
        UUID family = family(token);
        // Simulate a session that started 90 days ago and has been refreshed regularly ever since.
        jdbc.update("update refresh_tokens set created_at = now() - interval '90 days' where family_id = ?", family);

        for (int i = 0; i < 6; i++) {
            token = cookieValue(refresh(token).andExpect(status().isOk()).andReturn(), REFRESH_COOKIE);
            assertThat(col(token, "expires_at"))
                    .isCloseTo(Instant.now().plus(SEVEN_DAYS), within(Duration.ofMinutes(1)));
        }
        assertThat(family(token)).isEqualTo(family); // still the same session
    }

    @Test
    void absoluteSessionDeadlineNoLongerExistsInTheSchema() {
        assertThat(jdbc.queryForObject("select count(*) from information_schema.columns "
                + "where table_name = 'refresh_tokens' and column_name = 'session_expires_at'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from information_schema.table_constraints "
                + "where constraint_name = 'ck_refresh_tokens_expiry_within_session'", Integer.class)).isZero();
    }

    @Test
    void expiredRefreshTokenIsRejected() throws Exception {
        String token = newSession().refreshToken();
        jdbc.update("update refresh_tokens set expires_at = now() - interval '1 second' where token_hash = ?",
                RefreshTokenService.hash(token));

        assertThatThrownBy(() -> refreshTokenService.rotate(token))
                .isInstanceOf(InvalidRefreshTokenException.class).hasMessage("Refresh token has expired");
        refresh(token).andExpect(status().isUnauthorized());
    }

    @Test
    void reuseStillRevokesTheFamilyAfterExtensions() throws Exception {
        String t1 = newSession().refreshToken();
        String t2 = cookieValue(refresh(t1).andExpect(status().isOk()).andReturn(), REFRESH_COOKIE);
        String t3 = cookieValue(refresh(t2).andExpect(status().isOk()).andReturn(), REFRESH_COOKIE);

        refresh(t1).andExpect(status().isUnauthorized());
        refresh(t3).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where family_id = ? "
                + "and revoked_at is null", Integer.class, family(t1))).isZero();
    }

    @Test
    void logoutStillRevokesTheSession() throws Exception {
        String t1 = newSession().refreshToken();
        String t2 = cookieValue(refresh(t1).andExpect(status().isOk()).andReturn(), REFRESH_COOKIE);

        logout(t2).andExpect(status().isNoContent());
        refresh(t2).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where family_id = ? "
                + "and revoked_at is null", Integer.class, family(t1))).isZero();
    }

    private Instant col(String rawToken, String column) {
        return jdbc.queryForObject("select " + column + " from refresh_tokens where token_hash = ?", Instant.class,
                RefreshTokenService.hash(rawToken));
    }

    private UUID family(String rawToken) {
        return jdbc.queryForObject("select family_id from refresh_tokens where token_hash = ?", UUID.class,
                RefreshTokenService.hash(rawToken));
    }
}
