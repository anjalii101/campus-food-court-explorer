package com.campusfoodcourt.campus_foodcourt.auth;

import com.campusfoodcourt.campus_foodcourt.entity.AccountStatus;
import com.campusfoodcourt.campus_foodcourt.service.AccountStatusService;
import com.campusfoodcourt.campus_foodcourt.support.AbstractAuthApiTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Only ACTIVE accounts can log in, refresh or use an access token; inactive accounts can still log out. */
class AccountStatusEnforcementTest extends AbstractAuthApiTest {

    @Autowired
    private AccountStatusService accountStatusService;

    // ---------------------------------------------------------------- registration

    @Test
    void registrationCreatesAnActiveUser() throws Exception {
        Session session = newSession();
        assertThat(statusOf(session.email())).isEqualTo("ACTIVE");
    }

    @Test
    void clientCannotChooseItsOwnStatusOrRole() throws Exception {
        String email = uniqueEmail();
        mvc.perform(withCsrf(jsonPost("/api/auth/register", Map.of("name", "Sneaky", "email", email,
                        "password", PASSWORD, "accountStatus", "SUSPENDED", "account_status", "DEACTIVATED",
                        "role", "ROLE_ADMIN"))))
                .andExpect(status().isCreated());

        assertThat(statusOf(email)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select role from users where email = ?", String.class, email))
                .isEqualTo("ROLE_USER");
    }

    // ---------------------------------------------------------------- login

    @Test
    void activeUserCanLogIn() throws Exception {
        Session session = newSession();
        MvcResult result = login(session.email(), PASSWORD).andExpect(status().isOk()).andReturn();
        assertThat(json(result).path("token").asText()).isNotBlank();
        assertThat(setCookieHeader(result, REFRESH_COOKIE)).isPresent();
    }

    @ParameterizedTest
    @EnumSource(value = AccountStatus.class, names = {"SUSPENDED", "DEACTIVATED"})
    void inactiveUserCannotLogInAndGetsNoTokens(AccountStatus inactive) throws Exception {
        Session session = newSession();
        setStatus(session.email(), inactive);
        int tokensBefore = tokenCount(session.email());

        MvcResult result = login(session.email(), PASSWORD).andExpect(status().isUnauthorized()).andReturn();

        assertThat(json(result).has("token")).isFalse();
        assertThat(setCookieHeader(result, REFRESH_COOKIE)).isEmpty();
        assertThat(tokenCount(session.email())).as("no refresh token issued").isEqualTo(tokensBefore);
    }

    @Test
    void loginFailureForInactiveAccountsIsIndistinguishableFromBadCredentials() throws Exception {
        Session suspended = newSession();
        Session deactivated = newSession();
        Session active = newSession();
        setStatus(suspended.email(), AccountStatus.SUSPENDED);
        setStatus(deactivated.email(), AccountStatus.DEACTIVATED);

        String suspendedBody = loginFailureBody(suspended.email(), PASSWORD);
        String deactivatedBody = loginFailureBody(deactivated.email(), PASSWORD);
        String wrongPasswordBody = loginFailureBody(active.email(), "wrong-password-123");
        String unknownEmailBody = loginFailureBody(uniqueEmail(), PASSWORD);

        assertThat(suspendedBody).isEqualTo(deactivatedBody).isEqualTo(wrongPasswordBody).isEqualTo(unknownEmailBody);
        assertThat(suspendedBody.toLowerCase()).doesNotContain("suspend", "deactivat", "disabled", "locked", "status\":\"");
    }

    // ---------------------------------------------------------------- existing access tokens

    @ParameterizedTest
    @EnumSource(value = AccountStatus.class, names = {"SUSPENDED", "DEACTIVATED"})
    void existingAccessTokenIsRejectedOnceTheAccountIsInactive(AccountStatus inactive) throws Exception {
        Session session = newSession();
        me(session.accessToken()).andExpect(status().isOk());

        setStatus(session.email(), inactive);

        MvcResult rejected = me(session.accessToken()).andExpect(status().isUnauthorized()).andReturn();
        assertThat(rejected.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
    }

    @Test
    void accessTokenWorksAgainWhenTheAccountIsReactivated() throws Exception {
        Session session = newSession();
        setStatus(session.email(), AccountStatus.SUSPENDED);
        me(session.accessToken()).andExpect(status().isUnauthorized());

        setStatus(session.email(), AccountStatus.ACTIVE);
        me(session.accessToken()).andExpect(status().isOk());
    }

    @Test
    void rejectedAccessTokenResponseDoesNotRevealTheAccountStatus() throws Exception {
        Session suspended = newSession();
        Session deactivated = newSession();
        setStatus(suspended.email(), AccountStatus.SUSPENDED);
        setStatus(deactivated.email(), AccountStatus.DEACTIVATED);

        MvcResult suspendedResult = me(suspended.accessToken()).andExpect(status().isUnauthorized()).andReturn();
        MvcResult deactivatedResult = me(deactivated.accessToken()).andExpect(status().isUnauthorized()).andReturn();
        MvcResult garbageResult = me("not-a-jwt").andExpect(status().isUnauthorized()).andReturn();

        String body = suspendedResult.getResponse().getContentAsString();
        assertThat(body)
                .isEqualTo(deactivatedResult.getResponse().getContentAsString())
                .isEqualTo(garbageResult.getResponse().getContentAsString());
        assertThat(body.toLowerCase()).doesNotContain("suspend", "deactivat", "disabled", "locked", "status\":\"");
    }

    // ---------------------------------------------------------------- refresh

    @ParameterizedTest
    @EnumSource(value = AccountStatus.class, names = {"SUSPENDED", "DEACTIVATED"})
    void inactiveUserCannotRefreshAndGetsNoSuccessor(AccountStatus inactive) throws Exception {
        Session session = newSession();
        UUID family = row(session.refreshToken(), "family_id", UUID.class);
        setStatus(session.email(), inactive);

        MvcResult result = refresh(session.refreshToken()).andExpect(status().isUnauthorized()).andReturn();

        assertThat(json(result).has("token")).isFalse();
        assertThat(setCookieHeader(result, REFRESH_COOKIE).orElseThrow()).contains("Max-Age=0");
        // No rotation was committed: no successor, and the presented token was not marked ROTATED.
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where family_id = ?", Integer.class,
                family)).isOne();
        assertThat(row(session.refreshToken(), "replaced_by_id", Long.class)).isNull();
        // Suspension leaves the token active (the session can resume); deactivation has revoked it.
        assertThat(row(session.refreshToken(), "revoked_reason", String.class))
                .isEqualTo(inactive == AccountStatus.SUSPENDED ? null : "ACCOUNT_DEACTIVATED");
    }

    @Test
    void refreshIsRefusedForAnInactiveStatusWrittenDirectlyToTheDatabase() throws Exception {
        // Defence in depth: even if the status is changed without AccountStatusService (so no tokens were
        // revoked), the refresh path itself refuses to issue a successor and leaves the token untouched.
        Session session = newSession();
        jdbc.update("update users set account_status = 'DEACTIVATED' where email = ?", session.email());

        refresh(session.refreshToken()).andExpect(status().isUnauthorized());

        assertThat(row(session.refreshToken(), "revoked_at", Instant.class)).isNull();
        assertThat(row(session.refreshToken(), "replaced_by_id", Long.class)).isNull();
    }

    @Test
    void refreshFailureForInactiveAccountsIsIndistinguishableFromAnInvalidToken() throws Exception {
        Session suspended = newSession();
        Session deactivated = newSession();
        setStatus(suspended.email(), AccountStatus.SUSPENDED);
        setStatus(deactivated.email(), AccountStatus.DEACTIVATED);

        String suspendedBody = refresh(suspended.refreshToken()).andExpect(status().isUnauthorized()).andReturn()
                .getResponse().getContentAsString();
        String deactivatedBody = refresh(deactivated.refreshToken()).andExpect(status().isUnauthorized()).andReturn()
                .getResponse().getContentAsString();
        String unknownBody = refresh("not-a-real-token").andExpect(status().isUnauthorized()).andReturn()
                .getResponse().getContentAsString();

        assertThat(suspendedBody).isEqualTo(deactivatedBody).isEqualTo(unknownBody);
        assertThat(suspendedBody.toLowerCase()).doesNotContain("suspend", "deactivat", "disabled", "locked", "status\":\"");
    }

    @Test
    void activeUserStillRotatesNormally() throws Exception {
        Session session = newSession();
        MvcResult result = refresh(session.refreshToken()).andExpect(status().isOk()).andReturn();
        String next = cookieValue(result, REFRESH_COOKIE);

        assertThat(next).isNotEqualTo(session.refreshToken());
        assertThat(row(session.refreshToken(), "replaced_by_id", Long.class)).isEqualTo(row(next, "id", Long.class));
        me(json(result).path("token").asText()).andExpect(status().isOk());
    }

    @Test
    void refreshWorksAgainWhenASuspendedAccountIsReactivated() throws Exception {
        Session session = newSession();
        setStatus(session.email(), AccountStatus.SUSPENDED);
        refresh(session.refreshToken()).andExpect(status().isUnauthorized());

        setStatus(session.email(), AccountStatus.ACTIVE);
        refresh(session.refreshToken()).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- logout

    @ParameterizedTest
    @EnumSource(value = AccountStatus.class, names = {"SUSPENDED", "DEACTIVATED"})
    void inactiveUserCanStillLogOut(AccountStatus inactive) throws Exception {
        Session session = newSession();
        UUID family = row(session.refreshToken(), "family_id", UUID.class);
        setStatus(session.email(), inactive);

        MvcResult result = logout(session.refreshToken()).andExpect(status().isNoContent()).andReturn();

        assertThat(setCookieHeader(result, REFRESH_COOKIE).orElseThrow()).contains("Max-Age=0");
        // A suspended session is ended by the logout; a deactivated one was already revoked and keeps that reason.
        assertThat(row(session.refreshToken(), "revoked_reason", String.class))
                .isEqualTo(inactive == AccountStatus.SUSPENDED ? "LOGOUT" : "ACCOUNT_DEACTIVATED");
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where family_id = ? and revoked_at is null",
                Integer.class, family)).isZero();

        setStatus(session.email(), AccountStatus.ACTIVE);
        refresh(session.refreshToken()).andExpect(status().isUnauthorized()); // the session stays ended
    }

    // ---------------------------------------------------------------- helpers

    private org.springframework.test.web.servlet.ResultActions me(String accessToken) throws Exception {
        return mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));
    }

    private String loginFailureBody(String email, String password) throws Exception {
        MvcResult result = login(email, password).andExpect(status().isUnauthorized()).andReturn();
        assertThat(setCookieHeader(result, REFRESH_COOKIE)).isEmpty();
        return result.getResponse().getContentAsString();
    }

    /** Changes the status the way the application does, including deactivation's session revocation. */
    private void setStatus(String email, AccountStatus status) {
        Long userId = jdbc.queryForObject("select id from users where email = ?", Long.class, email);
        accountStatusService.changeStatus(userId, status);
    }

    private String statusOf(String email) {
        return jdbc.queryForObject("select account_status from users where email = ?", String.class, email);
    }

    private int tokenCount(String email) {
        return jdbc.queryForObject("select count(*) from refresh_tokens t join users u on u.id = t.user_id"
                + " where u.email = ?", Integer.class, email);
    }

    private <T> T row(String rawToken, String column, Class<T> type) {
        return jdbc.queryForObject("select " + column + " from refresh_tokens where token_hash = ?", type,
                RefreshTokenService.hash(rawToken));
    }
}
