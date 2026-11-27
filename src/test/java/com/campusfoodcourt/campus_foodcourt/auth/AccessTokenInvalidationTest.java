package com.campusfoodcourt.campus_foodcourt.auth;

import com.campusfoodcourt.campus_foodcourt.entity.AccountStatus;
import com.campusfoodcourt.campus_foodcourt.security.JwtUtils;
import com.campusfoodcourt.campus_foodcourt.service.AccountStatusService;
import com.campusfoodcourt.campus_foodcourt.support.AbstractAuthApiTest;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Access tokens issued before a deactivation never work again, even after reactivation; suspension does not
 * invalidate them (users.token_version, V9).
 */
class AccessTokenInvalidationTest extends AbstractAuthApiTest {

    @Autowired
    private AccountStatusService accountStatusService;
    @Autowired
    private JwtUtils jwtUtils;
    @Autowired
    private JwtProperties jwtProperties;

    @Test
    void accessTokenIssuedBeforeDeactivationStaysInvalidAfterReactivation() throws Exception {
        Session session = newSession();
        Long userId = userId(session.email());

        me(session.accessToken()).andExpect(status().isOk());                         // 1. works before

        accountStatusService.changeStatus(userId, AccountStatus.DEACTIVATED);
        me(session.accessToken()).andExpect(status().isUnauthorized());               // 2. rejected immediately

        accountStatusService.changeStatus(userId, AccountStatus.ACTIVE);
        MvcResult afterReactivation = me(session.accessToken()).andExpect(status().isUnauthorized()).andReturn();
        assertThat(afterReactivation.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
        assertThat(jwtUtils.verify(session.accessToken()).tokenVersion())             // 3. still signed and unexpired,
                .isLessThan(tokenVersion(userId));                                    //    but superseded
    }

    @Test
    void freshLoginAfterReactivationIssuesWorkingTokens() throws Exception {
        Session old = newSession();
        Long userId = userId(old.email());
        accountStatusService.changeStatus(userId, AccountStatus.DEACTIVATED);
        accountStatusService.changeStatus(userId, AccountStatus.ACTIVE);

        Session fresh = Session.of(old.email(), login(old.email(), PASSWORD).andExpect(status().isOk()).andReturn(),
                objectMapper);

        me(fresh.accessToken()).andExpect(status().isOk());                           // 4. fresh login works
        assertThat(jwtUtils.verify(fresh.accessToken()).tokenVersion()).isEqualTo(tokenVersion(userId));
        MvcResult refreshed = refresh(fresh.refreshToken()).andExpect(status().isOk()).andReturn();
        me(json(refreshed).path("token").asText()).andExpect(status().isOk());       // refreshed one too
        me(old.accessToken()).andExpect(status().isUnauthorized());                   // old one still dead
    }

    @Test
    void suspensionDoesNotInvalidateAccessTokens() throws Exception {
        Session session = newSession();
        Long userId = userId(session.email());
        long versionBefore = tokenVersion(userId);

        accountStatusService.changeStatus(userId, AccountStatus.SUSPENDED);
        me(session.accessToken()).andExpect(status().isUnauthorized());
        accountStatusService.changeStatus(userId, AccountStatus.ACTIVE);

        me(session.accessToken()).andExpect(status().isOk());                         // 5. unexpired token resumes
        assertThat(tokenVersion(userId)).isEqualTo(versionBefore);
    }

    @Test
    void onlyDeactivationChangesTheTokenVersion() throws Exception {
        Long userId = userId(newSession().email());
        assertThat(tokenVersion(userId)).isZero();

        accountStatusService.changeStatus(userId, AccountStatus.SUSPENDED);
        accountStatusService.changeStatus(userId, AccountStatus.ACTIVE);
        assertThat(tokenVersion(userId)).isZero();

        accountStatusService.changeStatus(userId, AccountStatus.DEACTIVATED);
        assertThat(tokenVersion(userId)).isOne();
        accountStatusService.changeStatus(userId, AccountStatus.DEACTIVATED); // repeated: not a transition
        assertThat(tokenVersion(userId)).as("repeated deactivation does not increment again").isOne();
        accountStatusService.changeStatus(userId, AccountStatus.ACTIVE);
        assertThat(tokenVersion(userId)).isOne();

        accountStatusService.changeStatus(userId, AccountStatus.DEACTIVATED); // a new transition increments again
        assertThat(tokenVersion(userId)).isEqualTo(2);
    }

    @Test
    void suspendedToDeactivatedIsATransitionAndIncrementsTheVersion() throws Exception {
        Session session = newSession();
        Long userId = userId(session.email());
        accountStatusService.changeStatus(userId, AccountStatus.SUSPENDED);

        accountStatusService.changeStatus(userId, AccountStatus.DEACTIVATED);

        assertThat(tokenVersion(userId)).isOne();
        accountStatusService.changeStatus(userId, AccountStatus.ACTIVE);
        me(session.accessToken()).andExpect(status().isUnauthorized());
        refresh(session.refreshToken()).andExpect(status().isUnauthorized());
    }

    @Test
    void refreshedAccessTokenCarriesTheCurrentVersion() throws Exception {
        Session session = newSession();
        MvcResult refreshed = refresh(session.refreshToken()).andExpect(status().isOk()).andReturn();
        assertThat(jwtUtils.verify(json(refreshed).path("token").asText()).tokenVersion())
                .isEqualTo(tokenVersion(userId(session.email())));
    }

    @Test
    void validlySignedTokenWithoutVersionIsRejected() throws Exception {
        // Shape of an access token issued before V9: correctly signed and unexpired, but without token_version.
        Session session = newSession();
        Instant now = Instant.now();
        String legacy = Jwts.builder().issuer(jwtProperties.issuer()).subject(session.email())
                .claim(JwtUtils.TOKEN_TYPE_CLAIM, JwtUtils.ACCESS_TOKEN_TYPE)
                .issuedAt(Date.from(now)).expiration(Date.from(now.plus(5, ChronoUnit.MINUTES)))
                .signWith(Keys.hmacShaKeyFor(jwtProperties.decodedSecret()), Jwts.SIG.HS256).compact();

        me(legacy).andExpect(status().isUnauthorized());
        me(session.accessToken()).andExpect(status().isOk());
    }

    @Test
    void tokenWithAForgedHigherVersionIsRejected() throws Exception {
        // Versions must match exactly; a token claiming a future version (only possible with the key) is refused.
        Session session = newSession();
        String future = jwtUtils.generateToken(session.email(), tokenVersion(userId(session.email())) + 1);
        me(future).andExpect(status().isUnauthorized());
    }

    private ResultActions me(String accessToken) throws Exception {
        return mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));
    }

    private Long userId(String email) {
        return jdbc.queryForObject("select id from users where email = ?", Long.class, email);
    }

    private long tokenVersion(Long userId) {
        return jdbc.queryForObject("select token_version from users where id = ?", Long.class, userId);
    }
}
