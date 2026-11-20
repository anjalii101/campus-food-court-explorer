package com.campusfoodcourt.campus_foodcourt.auth;

import com.campusfoodcourt.campus_foodcourt.security.JwtUtils;
import com.campusfoodcourt.campus_foodcourt.support.AbstractAuthApiTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * If minting the access token fails, nothing is committed: no revoked-but-unreplaced refresh token,
 * no half-registered user, no extra session.
 */
class AuthFailureSafetyTest extends AbstractAuthApiTest {

    @MockitoSpyBean
    private JwtUtils jwtUtils;

    @Test
    void failedAccessTokenDuringRefreshLeavesOldTokenValid() throws Exception {
        Session session = newSession();
        UUID family = family(session.refreshToken());
        doThrow(new IllegalStateException("signing failed")).when(jwtUtils).generateToken(session.email());

        assertThatThrownBy(() -> refresh(session.refreshToken())).hasRootCauseMessage("signing failed");

        assertThat(revokedAt(session.refreshToken())).as("rotation must be rolled back").isNull();
        assertThat(count("select count(*) from refresh_tokens where family_id = ?", family)).isOne();

        org.mockito.Mockito.reset(jwtUtils);
        refresh(session.refreshToken()).andExpect(status().isOk()); // old token still usable
    }

    @Test
    void failedAccessTokenDuringRegistrationCreatesNoUser() throws Exception {
        String email = uniqueEmail();
        doThrow(new IllegalStateException("signing failed")).when(jwtUtils).generateToken(email);

        assertThatThrownBy(() -> register("Ghost", email, PASSWORD)).hasRootCauseMessage("signing failed");

        assertThat(count("select count(*) from users where email = ?", email)).isZero();
        org.mockito.Mockito.reset(jwtUtils);
        register("Ghost", email, PASSWORD).andExpect(status().isCreated()); // email not left reserved
    }

    @Test
    void failedAccessTokenDuringLoginCreatesNoSession() throws Exception {
        Session session = newSession();
        Long userId = jdbc.queryForObject("select id from users where email = ?", Long.class, session.email());
        doThrow(new IllegalStateException("signing failed")).when(jwtUtils).generateToken(session.email());

        assertThatThrownBy(() -> login(session.email(), PASSWORD)).hasRootCauseMessage("signing failed");

        assertThat(count("select count(*) from refresh_tokens where user_id = ?", userId)).isOne();
    }

    private UUID family(String raw) {
        return jdbc.queryForObject("select family_id from refresh_tokens where token_hash = ?", UUID.class,
                RefreshTokenService.hash(raw));
    }

    private Instant revokedAt(String raw) {
        return jdbc.queryForObject("select revoked_at from refresh_tokens where token_hash = ?", Instant.class,
                RefreshTokenService.hash(raw));
    }

    private int count(String sql, Object arg) {
        return jdbc.queryForObject(sql, Integer.class, arg);
    }
}
