package com.campusfoodcourt.campus_foodcourt.auth;

import com.campusfoodcourt.campus_foodcourt.dto.AuthRequest;
import com.campusfoodcourt.campus_foodcourt.entity.AccountStatus;
import com.campusfoodcourt.campus_foodcourt.entity.User;
import com.campusfoodcourt.campus_foodcourt.exception.ResourceNotFoundException;
import com.campusfoodcourt.campus_foodcourt.repository.UserRepository;
import com.campusfoodcourt.campus_foodcourt.service.AccountStatusService;
import com.campusfoodcourt.campus_foodcourt.service.AuthService;
import com.campusfoodcourt.campus_foodcourt.support.AbstractAuthApiTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Suspension keeps sessions (they resume on reactivation); deactivation revokes every session of the user,
 * atomically with the status change, and reactivation does not bring them back.
 */
@ExtendWith(OutputCaptureExtension.class)
class AccountStatusTransitionTest extends AbstractAuthApiTest {

    private static final String THEFT_WARNING = "Refresh token reuse detected";

    @Autowired
    private AccountStatusService accountStatusService;
    @Autowired
    private RefreshTokenService refreshTokenService;
    @Autowired
    private AuthService authService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private TransactionTemplate transactionTemplate;

    /** One user with three active sessions ("devices"), one of which has been refreshed once. */
    private record Devices(String email, Long userId, String laptop, String phone, String tablet, String phoneAccess) {
    }

    private Devices threeDevices() throws Exception {
        Session laptop = newSession();
        MvcResult phoneLogin = login(laptop.email(), PASSWORD).andExpect(status().isOk()).andReturn();
        String tablet = cookieValue(login(laptop.email(), PASSWORD).andExpect(status().isOk()).andReturn(),
                REFRESH_COOKIE);
        MvcResult rotated = refresh(cookieValue(phoneLogin, REFRESH_COOKIE)).andExpect(status().isOk()).andReturn();
        return new Devices(laptop.email(), userId(laptop.email()), laptop.refreshToken(),
                cookieValue(rotated, REFRESH_COOKIE), tablet, json(rotated).path("token").asText());
    }

    // ---------------------------------------------------------------- suspension

    @Test
    void suspendingPreservesEveryActiveSession() throws Exception {
        Devices d = threeDevices();
        assertThat(activeTokens(d.userId())).isEqualTo(3);

        accountStatusService.changeStatus(d.userId(), AccountStatus.SUSPENDED);

        assertThat(accountStatusOf(d.userId())).isEqualTo("SUSPENDED");
        assertThat(activeTokens(d.userId())).isEqualTo(3);
        assertThat(activeFamilies(d.userId())).isEqualTo(3);
        assertThat(reasonCount(d.userId(), "ACCOUNT_DEACTIVATED")).isZero();
    }

    @Test
    void suspendedUserCannotRefreshAnySession() throws Exception {
        Devices d = threeDevices();
        accountStatusService.changeStatus(d.userId(), AccountStatus.SUSPENDED);

        for (String token : List.of(d.laptop(), d.phone(), d.tablet())) {
            refresh(token).andExpect(status().isUnauthorized());
        }
        assertThat(activeTokens(d.userId())).as("refusals do not revoke anything").isEqualTo(3);
    }

    @Test
    void reactivatingASuspendedUserResumesThePreservedSessions() throws Exception {
        Devices d = threeDevices();
        accountStatusService.changeStatus(d.userId(), AccountStatus.SUSPENDED);
        refresh(d.laptop()).andExpect(status().isUnauthorized());

        accountStatusService.changeStatus(d.userId(), AccountStatus.ACTIVE);

        for (String token : List.of(d.laptop(), d.phone(), d.tablet())) {
            refresh(token).andExpect(status().isOk());
        }
        me(d.phoneAccess()).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- deactivation

    @Test
    void deactivatingRevokesEverySessionOnEveryDeviceWithTheDeactivationReason() throws Exception {
        Devices d = threeDevices();
        Session otherUser = newSession();
        long otherUserActiveBefore = activeTokens(userId(otherUser.email()));

        accountStatusService.changeStatus(d.userId(), AccountStatus.DEACTIVATED);

        assertThat(accountStatusOf(d.userId())).isEqualTo("DEACTIVATED");
        assertThat(activeTokens(d.userId())).isZero();
        // The three sessions' active tokens carry the new reason; the earlier rotation keeps ROTATED.
        assertThat(reasonCount(d.userId(), "ACCOUNT_DEACTIVATED")).isEqualTo(3);
        assertThat(reasonCount(d.userId(), "ROTATED")).isOne();
        for (String token : List.of(d.laptop(), d.phone(), d.tablet())) {
            assertThat(reasonOf(token)).isEqualTo("ACCOUNT_DEACTIVATED");
        }
        assertThat(activeTokens(userId(otherUser.email()))).as("other users are unaffected")
                .isEqualTo(otherUserActiveBefore);
    }

    @Test
    void deactivationKeepsTheReasonOfAlreadyEndedSessions() throws Exception {
        Devices d = threeDevices();
        logout(d.tablet()).andExpect(status().isNoContent());

        accountStatusService.changeStatus(d.userId(), AccountStatus.DEACTIVATED);

        assertThat(reasonOf(d.tablet())).isEqualTo("LOGOUT");
        assertThat(reasonCount(d.userId(), "ACCOUNT_DEACTIVATED")).isEqualTo(2);
    }

    @Test
    void deactivationIsAtomicWithSessionRevocation() throws Exception {
        Devices d = threeDevices();
        // Make the session-revocation UPDATE fail for this user only, after the status UPDATE has been flushed.
        jdbc.execute("create or replace function cfc_test_fail_deactivation() returns trigger as $$ begin "
                + "if new.revoked_reason = 'ACCOUNT_DEACTIVATED' and new.user_id = " + d.userId() + " then "
                + "raise exception 'simulated failure while revoking sessions'; end if; return new; end $$ "
                + "language plpgsql");
        jdbc.execute("create trigger cfc_test_fail_deactivation before update on refresh_tokens "
                + "for each row execute function cfc_test_fail_deactivation()");
        try {
            assertThatThrownBy(() -> accountStatusService.changeStatus(d.userId(), AccountStatus.DEACTIVATED))
                    .isInstanceOf(DataAccessException.class)
                    .hasStackTraceContaining("simulated failure while revoking sessions");
        } finally {
            jdbc.execute("drop trigger cfc_test_fail_deactivation on refresh_tokens");
            jdbc.execute("drop function cfc_test_fail_deactivation()");
        }

        // Nothing was committed: the account is still ACTIVE and every session still works.
        assertThat(accountStatusOf(d.userId())).isEqualTo("ACTIVE");
        assertThat(activeTokens(d.userId())).isEqualTo(3);
        refresh(d.laptop()).andExpect(status().isOk());
    }

    @Test
    void deactivatedUserCannotLogIn() throws Exception {
        Devices d = threeDevices();
        accountStatusService.changeStatus(d.userId(), AccountStatus.DEACTIVATED);

        MvcResult result = login(d.email(), PASSWORD).andExpect(status().isUnauthorized()).andReturn();
        assertThat(setCookieHeader(result, REFRESH_COOKIE)).isEmpty();
        assertThat(activeTokens(d.userId())).isZero();
    }

    @Test
    void deactivatedUserCannotUseAnExistingAccessToken() throws Exception {
        Devices d = threeDevices();
        me(d.phoneAccess()).andExpect(status().isOk());

        accountStatusService.changeStatus(d.userId(), AccountStatus.DEACTIVATED);

        me(d.phoneAccess()).andExpect(status().isUnauthorized());
    }

    @Test
    void deactivatedUserCannotRefreshAnOldTokenAndNoTheftAlertIsRaised(CapturedOutput output) throws Exception {
        Devices d = threeDevices();
        accountStatusService.changeStatus(d.userId(), AccountStatus.DEACTIVATED);
        long tokensBefore = allTokens(d.userId());

        for (String token : List.of(d.laptop(), d.phone(), d.tablet())) {
            MvcResult result = refresh(token).andExpect(status().isUnauthorized()).andReturn();
            assertThat(setCookieHeader(result, REFRESH_COOKIE).orElseThrow()).contains("Max-Age=0");
        }
        assertThat(allTokens(d.userId())).as("no successor issued").isEqualTo(tokensBefore);
        assertThat(output.getAll()).doesNotContain(THEFT_WARNING);
    }

    @Test
    void reactivatingADeactivatedUserDoesNotRestoreRevokedSessions() throws Exception {
        Devices d = threeDevices();
        accountStatusService.changeStatus(d.userId(), AccountStatus.DEACTIVATED);

        accountStatusService.changeStatus(d.userId(), AccountStatus.ACTIVE);

        assertThat(accountStatusOf(d.userId())).isEqualTo("ACTIVE");
        for (String token : List.of(d.laptop(), d.phone(), d.tablet())) {
            refresh(token).andExpect(status().isUnauthorized());
            assertThat(reasonOf(token)).isEqualTo("ACCOUNT_DEACTIVATED");
        }
        assertThat(activeTokens(d.userId())).isZero();
    }

    @Test
    void reactivatedUserCanStartANewSessionByLoggingIn() throws Exception {
        Devices d = threeDevices();
        accountStatusService.changeStatus(d.userId(), AccountStatus.DEACTIVATED);
        accountStatusService.changeStatus(d.userId(), AccountStatus.ACTIVE);

        Session fresh = Session.of(d.email(), login(d.email(), PASSWORD).andExpect(status().isOk()).andReturn(),
                objectMapper);

        me(fresh.accessToken()).andExpect(status().isOk());
        refresh(fresh.refreshToken()).andExpect(status().isOk());
        assertThat(activeTokens(d.userId())).isOne();
    }

    @Test
    void reuseDetectionAndLogoutStillWorkAfterAReactivation(CapturedOutput output) throws Exception {
        Devices d = threeDevices();
        accountStatusService.changeStatus(d.userId(), AccountStatus.SUSPENDED);
        accountStatusService.changeStatus(d.userId(), AccountStatus.ACTIVE);

        String next = cookieValue(refresh(d.laptop()).andExpect(status().isOk()).andReturn(), REFRESH_COOKIE);
        refresh(d.laptop()).andExpect(status().isUnauthorized()); // replay of a rotated token
        assertThat(output.getAll()).contains(THEFT_WARNING);
        assertThat(reasonOf(next)).isEqualTo("REUSE_DETECTED");

        logout(d.tablet()).andExpect(status().isNoContent());
        assertThat(reasonOf(d.tablet())).isEqualTo("LOGOUT");
        refresh(d.phone()).andExpect(status().isOk()); // the remaining session is unaffected
    }

    @Test
    void repeatedDeactivationIsSafeAndKeepsExistingRevocations() throws Exception {
        Devices d = threeDevices();
        logout(d.tablet()).andExpect(status().isNoContent());
        accountStatusService.changeStatus(d.userId(), AccountStatus.DEACTIVATED);
        List<Map<String, Object>> afterFirst = revocations(d.userId());
        long versionAfterFirst = tokenVersionOf(d.userId());

        accountStatusService.changeStatus(d.userId(), AccountStatus.DEACTIVATED);

        assertThat(accountStatusOf(d.userId())).isEqualTo("DEACTIVATED");
        assertThat(revocations(d.userId())).as("reasons and timestamps unchanged").isEqualTo(afterFirst);
        assertThat(tokenVersionOf(d.userId())).as("token version unchanged").isEqualTo(versionAfterFirst);
        assertThat(reasonOf(d.tablet())).isEqualTo("LOGOUT");
        assertThat(reasonOf(d.phone())).isEqualTo("ACCOUNT_DEACTIVATED");
    }

    @Test
    void statusChangeIgnoresUserStateAlreadyLoadedByTheCallingTransaction() throws Exception {
        Session session = newSession();
        Long id = userId(session.email());

        transactionTemplate.executeWithoutResult(tx -> {
            assertThat(userRepository.findById(id).orElseThrow().getAccountStatus()) // now cached as ACTIVE
                    .isEqualTo(AccountStatus.ACTIVE);
            // Meanwhile another transaction suspends the account and commits.
            runInOtherThread(() -> jdbc.update("update users set account_status = 'SUSPENDED' where id = ?", id));

            accountStatusService.changeStatus(id, AccountStatus.ACTIVE);
        });

        // An entity-based implementation would see "ACTIVE -> ACTIVE", write nothing, and leave it SUSPENDED.
        assertThat(accountStatusOf(id)).isEqualTo("ACTIVE");
    }

    @Test
    void changingTheStatusOfAnUnknownUserFails() {
        assertThatThrownBy(() -> accountStatusService.changeStatus(Long.MAX_VALUE, AccountStatus.DEACTIVATED))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------------------------------------------------------------- races

    /**
     * Stress companion to the deterministic interleavings in {@link AccountStatusConcurrencyTest}: deactivation
     * racing a refresh and a login of the same user with no forced order. Whatever the interleaving, once the
     * account is DEACTIVATED no active session may remain, and nothing may fail other than the normal refusals
     * (for example no deadlock).
     */
    @Test
    void deactivationRacingRefreshAndLoginLeavesNoActiveSession() throws Exception {
        String passwordHash = passwordEncoder.encode(PASSWORD);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            for (int round = 0; round < 15; round++) {
                String email = "race-" + UUID.randomUUID() + "@campus.test";
                User user = userRepository.save(User.builder().name("Race").email(email).password(passwordHash)
                        .role("ROLE_USER").build());
                String token = refreshTokenService.issueForNewSession(user).rawToken();

                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> futures = new ArrayList<>();
                futures.add(pool.submit(awaiting(start, () -> {
                    accountStatusService.changeStatus(user.getId(), AccountStatus.DEACTIVATED);
                    return null;
                })));
                futures.add(pool.submit(awaiting(start, () -> {
                    try {
                        refreshTokenService.rotate(token);
                    } catch (InvalidRefreshTokenException refused) {
                        // refused because the deactivation came first
                    }
                    return null;
                })));
                futures.add(pool.submit(awaiting(start, () -> {
                    try {
                        authService.login(new AuthRequest(email, PASSWORD));
                    } catch (BadCredentialsException refused) {
                        // refused because the deactivation came first
                    }
                    return null;
                })));
                start.countDown();
                for (Future<?> f : futures) {
                    f.get(30, TimeUnit.SECONDS); // rethrows any unexpected failure, e.g. a deadlock
                }

                assertThat(accountStatusOf(user.getId())).isEqualTo("DEACTIVATED");
                assertThat(activeTokens(user.getId())).as("round %d", round).isZero();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static void runInOtherThread(Runnable task) {
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            other.submit(task).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            other.shutdownNow();
        }
    }

    private long tokenVersionOf(Long userId) {
        return jdbc.queryForObject("select token_version from users where id = ?", Long.class, userId);
    }

    private List<Map<String, Object>> revocations(Long userId) {
        return jdbc.queryForList("select id, revoked_at, revoked_reason from refresh_tokens where user_id = ? order by id",
                userId);
    }

    private static Callable<Void> awaiting(CountDownLatch start, Callable<Void> task) {
        return () -> {
            start.await();
            return task.call();
        };
    }

    // ---------------------------------------------------------------- helpers

    private ResultActions me(String accessToken) throws Exception {
        return mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));
    }

    private Long userId(String email) {
        return jdbc.queryForObject("select id from users where email = ?", Long.class, email);
    }

    private String accountStatusOf(Long userId) {
        return jdbc.queryForObject("select account_status from users where id = ?", String.class, userId);
    }

    private long activeTokens(Long userId) {
        return jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ? and revoked_at is null",
                Long.class, userId);
    }

    private long allTokens(Long userId) {
        return jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ?", Long.class, userId);
    }

    private long activeFamilies(Long userId) {
        return jdbc.queryForObject("select count(distinct family_id) from refresh_tokens"
                + " where user_id = ? and revoked_at is null", Long.class, userId);
    }

    private long reasonCount(Long userId, String reason) {
        return jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ? and revoked_reason = ?",
                Long.class, userId, reason);
    }

    private String reasonOf(String rawToken) {
        return jdbc.queryForObject("select revoked_reason from refresh_tokens where token_hash = ?", String.class,
                RefreshTokenService.hash(rawToken));
    }
}
