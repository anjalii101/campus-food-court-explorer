package com.campusfoodcourt.campus_foodcourt.auth;

import com.campusfoodcourt.campus_foodcourt.entity.AccountStatus;
import com.campusfoodcourt.campus_foodcourt.service.AccountStatusService;
import com.campusfoodcourt.campus_foodcourt.support.AbstractAuthApiTest;
import com.campusfoodcourt.campus_foodcourt.support.MutableClock;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Timestamp-precision edge case. The application clock is frozen, so the old token, the deactivation, the
 * reactivation and the new login all happen at the very same instant, and both tokens carry an identical
 * {@code iat} (JWT {@code iat} has only one-second precision anyway). A "tokens issued before X" timestamp rule
 * could not tell them apart; the token version does.
 */
class AccessTokenVersionBoundaryTest extends AbstractAuthApiTest {

    private static final MutableClock FROZEN = new MutableClock(Instant.parse("2026-11-28T10:00:00.123456789Z"));

    @TestBean
    private Clock clock;

    static Clock clock() {
        return FROZEN;
    }

    @Autowired
    private AccountStatusService accountStatusService;

    @Test
    void tokensIssuedAtTheSameInstantAsTheDeactivationAreToldApartByVersion() throws Exception {
        Session before = newSession();
        Long userId = jdbc.queryForObject("select id from users where email = ?", Long.class, before.email());

        accountStatusService.changeStatus(userId, AccountStatus.DEACTIVATED); // same instant
        accountStatusService.changeStatus(userId, AccountStatus.ACTIVE);      // same instant
        Session after = Session.of(before.email(), login(before.email(), PASSWORD).andExpect(status().isOk())
                .andReturn(), objectMapper);                                     // same instant

        JsonNode oldClaims = claims(before.accessToken());
        JsonNode newClaims = claims(after.accessToken());
        assertThat(newClaims.path("iat").asLong()).as("identical issue time").isEqualTo(oldClaims.path("iat").asLong());
        assertThat(newClaims.path("exp").asLong()).isEqualTo(oldClaims.path("exp").asLong());
        assertThat(oldClaims.path("token_version").asLong()).isZero();
        assertThat(newClaims.path("token_version").asLong()).isOne();

        me(before.accessToken()).andExpect(status().isUnauthorized());
        me(after.accessToken()).andExpect(status().isOk());
    }

    @Test
    void tokenIssuedAtTheSameInstantAsASuspensionKeepsWorkingAfterReactivation() throws Exception {
        Session session = newSession();
        Long userId = jdbc.queryForObject("select id from users where email = ?", Long.class, session.email());

        accountStatusService.changeStatus(userId, AccountStatus.SUSPENDED);
        accountStatusService.changeStatus(userId, AccountStatus.ACTIVE);

        me(session.accessToken()).andExpect(status().isOk());
    }

    private ResultActions me(String accessToken) throws Exception {
        return mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));
    }

    private JsonNode claims(String jwt) throws Exception {
        String payload = new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
        return objectMapper.readTree(payload);
    }
}
