package com.campusfoodcourt.campus_foodcourt.auth;

import com.campusfoodcourt.campus_foodcourt.dto.AuthRequest;
import com.campusfoodcourt.campus_foodcourt.dto.AuthResponse;
import com.campusfoodcourt.campus_foodcourt.entity.AccountStatus;
import com.campusfoodcourt.campus_foodcourt.entity.User;
import com.campusfoodcourt.campus_foodcourt.repository.UserRepository;
import com.campusfoodcourt.campus_foodcourt.security.JwtUtils;
import com.campusfoodcourt.campus_foodcourt.service.AccountStatusService;
import com.campusfoodcourt.campus_foodcourt.service.AuthService;
import com.campusfoodcourt.campus_foodcourt.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.convention.TestBean;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deterministic interleavings of account-status changes with session operations.
 * <p>
 * A {@link PausingClock} stops one chosen thread at its first clock read, which in every operation here happens
 * <b>after</b> it has taken its row locks: refresh/logout (user FOR SHARE, token row), login (user FOR SHARE),
 * deactivation (users row lock, before revoking tokens). The other operation is then started and the test waits
 * until PostgreSQL reports it as waiting on a lock ({@code pg_stat_activity.wait_event_type = 'Lock'}), so each
 * test proves the blocking (or non-blocking) itself rather than relying on timing. Embedded PostgreSQL only.
 */
class AccountStatusConcurrencyTest extends AbstractIntegrationTest {

    private static final PausingClock CLOCK = new PausingClock();
    private static final String PASSWORD = "correct-horse-battery";

    @TestBean
    private Clock clock;

    static Clock clock() {
        return CLOCK;
    }

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
    private JdbcTemplate jdbc;
    @Autowired
    private JwtUtils jwtUtils;

    private ExecutorService pool;

    @BeforeEach
    void startPool() {
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void stopPool() {
        CLOCK.releaseAll();
        pool.shutdownNow();
    }

    // ---------------------------------------------------------------- refresh vs deactivation

    @Test
    void deactivationWaitsForAnInFlightRefreshAndThenRevokesItsSuccessor() throws Exception {
        User user = newUser();
        String token = refreshTokenService.issueForNewSession(user).rawToken();

        Pause pause = CLOCK.newPause();
        Future<RefreshTokenService.Rotation> refresh = pool.submit(paused(pause, () -> refreshTokenService.rotate(token)));
        pause.awaitReached(); // refresh holds the user share lock and the token lock

        Future<?> deactivation = pool.submit(() -> accountStatusService.changeStatus(user.getId(), AccountStatus.DEACTIVATED));
        awaitWaitingOnLock();
        assertThat(deactivation.isDone()).isFalse();

        pause.release();
        RefreshTokenService.Rotation rotation = refresh.get(30, TimeUnit.SECONDS); // read ACTIVE and succeeded
        String successor = rotation.next().rawToken();
        deactivation.get(30, TimeUnit.SECONDS);

        assertThat(reasonOf(token)).isEqualTo("ROTATED");
        assertThat(reasonOf(successor)).as("successor issued during deactivation is revoked too")
                .isEqualTo("ACCOUNT_DEACTIVATED");
        assertThat(activeTokens(user.getId())).isZero();
        assertThat(rotation.tokenVersion()).as("its access token carries a superseded version")
                .isLessThan(tokenVersion(user.getId()));
    }

    @Test
    void refreshWaitsForAnInFlightDeactivationAndIsThenRefused() throws Exception {
        User user = newUser();
        String token = refreshTokenService.issueForNewSession(user).rawToken();

        Pause pause = CLOCK.newPause();
        Future<?> deactivation = pool.submit(paused(pause, () -> {
            accountStatusService.changeStatus(user.getId(), AccountStatus.DEACTIVATED);
            return null;
        }));
        pause.awaitReached(); // status UPDATE done (row locked), tokens not yet revoked

        Future<RefreshTokenService.Rotation> refresh = pool.submit(() -> refreshTokenService.rotate(token));
        awaitWaitingOnLock();
        assertThat(refresh.isDone()).isFalse();

        pause.release();
        deactivation.get(30, TimeUnit.SECONDS);
        assertThatThrownBy(() -> refresh.get(30, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(InvalidRefreshTokenException.class);

        assertThat(reasonOf(token)).isEqualTo("ACCOUNT_DEACTIVATED");
        assertThat(allTokens(user.getId())).as("no successor was created").isOne();
    }

    // ---------------------------------------------------------------- login vs deactivation

    @Test
    void deactivationWaitsForAnInFlightLoginAndThenRevokesTheNewSession() throws Exception {
        User user = newUser();

        Pause pause = CLOCK.newPause();
        Future<AuthResponse> login = pool.submit(paused(pause,
                () -> authService.login(new AuthRequest(user.getEmail(), PASSWORD))));
        pause.awaitReached(); // login holds the user share lock, about to insert its refresh token

        Future<?> deactivation = pool.submit(() -> accountStatusService.changeStatus(user.getId(), AccountStatus.DEACTIVATED));
        awaitWaitingOnLock();
        assertThat(deactivation.isDone()).isFalse();

        pause.release();
        AuthResponse issued = login.get(30, TimeUnit.SECONDS);
        deactivation.get(30, TimeUnit.SECONDS);

        assertThat(reasonOf(issued.getRefreshToken())).isEqualTo("ACCOUNT_DEACTIVATED");
        assertThat(activeTokens(user.getId())).isZero();
        assertThat(jwtUtils.verify(issued.getToken()).tokenVersion())
                .as("the access token issued during the deactivation carries a superseded version")
                .isLessThan(tokenVersion(user.getId()));
    }

    @Test
    void loginWaitsForAnInFlightDeactivationAndIsThenRefused() throws Exception {
        User user = newUser();

        Pause pause = CLOCK.newPause();
        Future<?> deactivation = pool.submit(paused(pause, () -> {
            accountStatusService.changeStatus(user.getId(), AccountStatus.DEACTIVATED);
            return null;
        }));
        pause.awaitReached();

        Future<AuthResponse> login = pool.submit(() -> authService.login(new AuthRequest(user.getEmail(), PASSWORD)));
        awaitWaitingOnLock(); // the BCrypt check runs first, outside the transaction; then the share lock waits
        assertThat(login.isDone()).isFalse();

        pause.release();
        deactivation.get(30, TimeUnit.SECONDS);
        assertThatThrownBy(() -> login.get(30, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(BadCredentialsException.class);
        assertThat(allTokens(user.getId())).isZero();
    }

    // ---------------------------------------------------------------- logout vs deactivation

    @Test
    void deactivationWaitsForAnInFlightLogoutWhichKeepsItsReason() throws Exception {
        User user = newUser();
        String loggingOut = refreshTokenService.issueForNewSession(user).rawToken();
        String otherDevice = refreshTokenService.issueForNewSession(user).rawToken();

        Pause pause = CLOCK.newPause();
        Future<?> logout = pool.submit(paused(pause, () -> {
            refreshTokenService.revokeSession(loggingOut);
            return null;
        }));
        pause.awaitReached(); // logout holds the user share lock and the token lock

        Future<?> deactivation = pool.submit(() -> accountStatusService.changeStatus(user.getId(), AccountStatus.DEACTIVATED));
        awaitWaitingOnLock();
        assertThat(deactivation.isDone()).isFalse();

        pause.release();
        logout.get(30, TimeUnit.SECONDS);
        deactivation.get(30, TimeUnit.SECONDS);

        assertThat(reasonOf(loggingOut)).isEqualTo("LOGOUT");
        assertThat(reasonOf(otherDevice)).isEqualTo("ACCOUNT_DEACTIVATED");
        assertThat(activeTokens(user.getId())).isZero();
    }

    @Test
    void logoutWaitsForAnInFlightDeactivationAndStillSucceeds() throws Exception {
        User user = newUser();
        String token = refreshTokenService.issueForNewSession(user).rawToken();

        Pause pause = CLOCK.newPause();
        Future<?> deactivation = pool.submit(paused(pause, () -> {
            accountStatusService.changeStatus(user.getId(), AccountStatus.DEACTIVATED);
            return null;
        }));
        pause.awaitReached();

        Future<?> logout = pool.submit(() -> refreshTokenService.revokeSession(token));
        awaitWaitingOnLock();
        assertThat(logout.isDone()).isFalse();

        pause.release();
        deactivation.get(30, TimeUnit.SECONDS);
        logout.get(30, TimeUnit.SECONDS); // no exception: logout works for inactive accounts

        assertThat(reasonOf(token)).as("the deactivation's reason is not overwritten").isEqualTo("ACCOUNT_DEACTIVATED");
    }

    // ---------------------------------------------------------------- deactivation vs deactivation

    @Test
    void concurrentDeactivationsApplyTheSideEffectsExactlyOnce() throws Exception {
        User user = newUser();
        String token = refreshTokenService.issueForNewSession(user).rawToken();

        Pause pause = CLOCK.newPause();
        Future<?> first = pool.submit(paused(pause, () -> {
            accountStatusService.changeStatus(user.getId(), AccountStatus.DEACTIVATED);
            return null;
        }));
        pause.awaitReached(); // status changed and version incremented, row locked, tokens not yet revoked

        Future<?> second = pool.submit(() -> accountStatusService.changeStatus(user.getId(), AccountStatus.DEACTIVATED));
        awaitWaitingOnLock();
        assertThat(second.isDone()).isFalse();

        pause.release();
        first.get(30, TimeUnit.SECONDS);
        second.get(30, TimeUnit.SECONDS); // re-checks against the committed row: already DEACTIVATED, does nothing

        assertThat(tokenVersion(user.getId())).as("incremented once, not twice").isOne();
        assertThat(reasonOf(token)).isEqualTo("ACCOUNT_DEACTIVATED");
        assertThat(activeTokens(user.getId())).isZero();
    }

    // ---------------------------------------------------------------- refresh vs refresh

    @Test
    void refreshesOfDifferentDevicesOfOneUserDoNotBlockEachOther() throws Exception {
        User user = newUser();
        String laptop = refreshTokenService.issueForNewSession(user).rawToken();
        String phone = refreshTokenService.issueForNewSession(user).rawToken();

        Pause pause = CLOCK.newPause();
        Future<RefreshTokenService.Rotation> laptopRefresh = pool.submit(paused(pause, () -> refreshTokenService.rotate(laptop)));
        pause.awaitReached(); // laptop refresh holds the user share lock

        // The phone refresh needs the same share lock: it must complete while the laptop refresh is still paused.
        assertThat(pool.submit(() -> refreshTokenService.rotate(phone)).get(30, TimeUnit.SECONDS).next().rawToken())
                .isNotBlank();

        pause.release();
        assertThat(laptopRefresh.get(30, TimeUnit.SECONDS).next().rawToken()).isNotBlank();
        assertThat(activeTokens(user.getId())).isEqualTo(2);
    }

    @Test
    void secondRefreshWithTheSameTokenWaitsAndIsDetectedAsReuse() throws Exception {
        User user = newUser();
        String token = refreshTokenService.issueForNewSession(user).rawToken();

        Pause pause = CLOCK.newPause();
        Future<RefreshTokenService.Rotation> first = pool.submit(paused(pause, () -> refreshTokenService.rotate(token)));
        pause.awaitReached(); // first refresh holds the token row lock

        Future<RefreshTokenService.Rotation> second = pool.submit(() -> refreshTokenService.rotate(token));
        awaitWaitingOnLock();
        assertThat(second.isDone()).isFalse();

        pause.release();
        String successor = first.get(30, TimeUnit.SECONDS).next().rawToken();
        assertThatThrownBy(() -> second.get(30, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(InvalidRefreshTokenException.class);

        assertThat(reasonOf(token)).isEqualTo("ROTATED");
        assertThat(reasonOf(successor)).isEqualTo("REUSE_DETECTED");
        assertThat(activeTokens(user.getId())).isZero();
    }

    // ---------------------------------------------------------------- helpers

    private User newUser() {
        return userRepository.save(User.builder().name("Concurrency")
                .email("concurrency-" + UUID.randomUUID() + "@campus.test")
                .password(passwordEncoder.encode(PASSWORD)).role("ROLE_USER").build());
    }

    private static <T> Callable<T> paused(Pause pause, Callable<T> task) {
        return () -> {
            pause.bindToCurrentThread();
            return task.call();
        };
    }

    /** Waits until some session of the test database is blocked on a row/transaction lock. */
    private void awaitWaitingOnLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("select count(*) from pg_stat_activity"
                    + " where datname = current_database() and wait_event_type = 'Lock'", Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Expected the second operation to wait on a database lock, but it never did");
    }

    private long activeTokens(Long userId) {
        return jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ? and revoked_at is null",
                Long.class, userId);
    }

    private long tokenVersion(Long userId) {
        return jdbc.queryForObject("select token_version from users where id = ?", Long.class, userId);
    }

    private long allTokens(Long userId) {
        return jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ?", Long.class, userId);
    }

    private String reasonOf(String rawToken) {
        return jdbc.queryForObject("select revoked_reason from refresh_tokens where token_hash = ?", String.class,
                RefreshTokenService.hash(rawToken));
    }

    /** One pause: the bound thread stops at its first clock read until released. */
    static final class Pause {
        private volatile Thread thread;
        private final AtomicBoolean fired = new AtomicBoolean();
        private final CountDownLatch reached = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        void bindToCurrentThread() {
            thread = Thread.currentThread();
        }

        void awaitReached() throws InterruptedException {
            if (!reached.await(20, TimeUnit.SECONDS)) {
                throw new AssertionError("The paused operation never read the clock");
            }
        }

        void release() {
            release.countDown();
        }

        void pauseIfBound() {
            if (thread == Thread.currentThread() && fired.compareAndSet(false, true)) {
                reached.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /** System clock that can pause one chosen thread; every other thread is never affected. */
    static final class PausingClock extends Clock {
        private volatile Pause pause;

        Pause newPause() {
            Pause p = new Pause();
            pause = p;
            return p;
        }

        void releaseAll() {
            Pause p = pause;
            if (p != null) {
                p.release();
            }
        }

        @Override
        public Instant instant() {
            Pause p = pause;
            if (p != null) {
                p.pauseIfBound();
            }
            return Instant.now();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
