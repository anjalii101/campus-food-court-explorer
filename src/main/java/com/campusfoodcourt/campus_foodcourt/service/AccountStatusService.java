package com.campusfoodcourt.campus_foodcourt.service;

import com.campusfoodcourt.campus_foodcourt.auth.RefreshTokenRepository;
import com.campusfoodcourt.campus_foodcourt.auth.RevocationReason;
import com.campusfoodcourt.campus_foodcourt.entity.AccountStatus;
import com.campusfoodcourt.campus_foodcourt.exception.ResourceNotFoundException;
import com.campusfoodcourt.campus_foodcourt.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;

/**
 * Changes a user's account status. Internal only: not exposed over HTTP (no admin API yet).
 * <ul>
 *   <li><b>SUSPENDED</b>: sessions are kept. Login, access tokens and refresh are refused while suspended, and
 *       unexpired sessions resume if the account becomes ACTIVE again.</li>
 *   <li><b>DEACTIVATED</b>: every active refresh token of the user, in every family (all devices), is revoked
 *       with ACCOUNT_DEACTIVATED, and the access-token version is incremented, so every access token issued so
 *       far is invalid for good; all in the same transaction as the status change. Reactivation restores neither;
 *       the user must log in again.</li>
 *   <li><b>ACTIVE</b>: only the status changes; no session is revoked or restored.</li>
 * </ul>
 * The status UPDATE row-locks the user first, so a concurrent login, refresh or logout (which share-lock the same
 * row before touching tokens) either finishes before the change, and any token it created is then revoked, or
 * runs after it and sees the new status.
 */
@Service
public class AccountStatusService {

    private static final Logger log = LoggerFactory.getLogger(AccountStatusService.class);

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final Clock clock;

    public AccountStatusService(UserRepository userRepository, RefreshTokenRepository refreshTokenRepository,
                                Clock clock) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.clock = clock;
    }

    /**
     * Sets the status; a transition to DEACTIVATED also invalidates all access tokens and revokes all active
     * sessions. All or nothing, and idempotent: repeating a change is harmless, and repeating a deactivation
     * (DEACTIVATED to DEACTIVATED) changes nothing at all: no version increment, no revocation.
     * <p>
     * Both writes are database statements, not entity changes, so the result never depends on User or
     * RefreshToken state a calling transaction may already hold.
     */
    @Transactional
    public void changeStatus(Long userId, AccountStatus newStatus) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(newStatus, "newStatus");
        if (newStatus != AccountStatus.DEACTIVATED) {
            // UPDATE users row-locks the user (waits for in-flight logins/refreshes, which hold FOR SHARE).
            if (userRepository.updateAccountStatus(userId, newStatus) == 0) {
                throw new ResourceNotFoundException("User not found");
            }
            log.info("Account of user id {} set to {}", userId, newStatus);
            return;
        }

        // 1. Conditional UPDATE: row-locks the user and matches only on a real transition to DEACTIVATED, so the side
        //    effects below run exactly once per deactivation, also for repeated or concurrent requests.
        if (userRepository.updateAccountStatusIfDifferent(userId, AccountStatus.DEACTIVATED) == 0) {
            if (!userRepository.existsById(userId)) {
                throw new ResourceNotFoundException("User not found");
            }
            log.info("Account of user id {} is already {}; nothing changed", userId, newStatus);
            return;
        }
        // 2. Every access token issued so far (including by a login/refresh that finished just before step 1)
        //    carries an older version from now on, and stays invalid after a reactivation.
        userRepository.incrementTokenVersion(userId);
        // 3. A later statement, so it also sees tokens committed by sessions that finished before step 1.
        int revoked = refreshTokenRepository.revokeAllActiveForUser(userId, clock.instant(),
                RevocationReason.ACCOUNT_DEACTIVATED);
        log.info("Account of user id {} set to {}; revoked {} active refresh token(s)", userId, newStatus, revoked);
    }
}
