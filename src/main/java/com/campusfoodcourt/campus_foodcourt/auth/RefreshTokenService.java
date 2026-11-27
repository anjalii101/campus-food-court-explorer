package com.campusfoodcourt.campus_foodcourt.auth;

import com.campusfoodcourt.campus_foodcourt.entity.AccountStatus;
import com.campusfoodcourt.campus_foodcourt.entity.User;
import com.campusfoodcourt.campus_foodcourt.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Issues and rotates opaque refresh tokens.
 * <ul>
 *   <li>Each login starts a new token family.</li>
 *   <li>Every refresh revokes the presented token (ROTATED) and issues a successor in the same family that is
 *       valid for a full refresh-token TTL from now (rolling inactivity expiry). There is no absolute session
 *       lifetime: a session lasts as long as it is refreshed within the TTL.</li>
 *   <li>Presenting a ROTATED token again is treated as theft: the whole family is revoked (REUSE_DETECTED)
 *       and a warning is logged. Presenting a token revoked by LOGOUT or ACCOUNT_DEACTIVATED is simply rejected,
 *       without an alert.</li>
 *   <li>Only ACTIVE users get a successor. A SUSPENDED user's token is left untouched, so the session can resume
 *       after reactivation; deactivation revokes the tokens themselves (AccountStatusService).</li>
 * </ul>
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository repository;
    private final UserRepository userRepository;
    private final AuthProperties authProperties;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository repository, UserRepository userRepository,
                               AuthProperties authProperties, Clock clock) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.authProperties = authProperties;
        this.clock = clock;
    }

    /** A newly issued raw token. The raw value is returned to the client once and never persisted. */
    public record IssuedToken(String rawToken, Instant expiresAt) {
    }

    /**
     * Result of a successful rotation: who the token belonged to, the user's current access-token version (read
     * under the owner's share lock) and the successor.
     */
    public record Rotation(Long userId, String email, long tokenVersion, IssuedToken next) {
    }

    @Transactional
    public IssuedToken issueForNewSession(User user) {
        return issue(user, UUID.randomUUID()).issued();
    }

    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public Rotation rotate(String rawToken) {
        RefreshToken current = findLockedAfterOwner(rawToken);
        Instant now = clock.instant();

        if (current.isRevoked()) {
            if (current.getRevokedReason() == RevocationReason.ROTATED) {
                int revoked = repository.revokeFamily(current.getFamilyId(), now, RevocationReason.REUSE_DETECTED);
                log.warn("Refresh token reuse detected for user id {} (family {}); revoked {} active token(s)",
                        current.getUser().getId(), current.getFamilyId(), revoked);
            } else {
                log.debug("Rejected refresh token already revoked ({}) for family {}",
                        current.getRevokedReason(), current.getFamilyId());
            }
            throw new InvalidRefreshTokenException("Refresh token is no longer valid");
        }
        if (current.isExpired(now)) {
            throw new InvalidRefreshTokenException("Refresh token has expired");
        }

        User user = current.getUser();
        // Read from the database while the owner's row is share-locked (no status change can be in flight), never
        // from entity state, which a calling transaction could hold from before the lock.
        if (!isActive(user.getId())) {
            // Checked before anything is written: the presented token is left exactly as it was (not rotated,
            // not revoked) and no successor exists. The client gets the same response as for any invalid token.
            log.debug("Rejected refresh for inactive user id {} (family {})", user.getId(), current.getFamilyId());
            throw new InvalidRefreshTokenException("Refresh token is no longer valid");
        }
        long tokenVersion = userRepository.findTokenVersionById(user.getId())
                .orElseThrow(() -> new InvalidRefreshTokenException("Refresh token is no longer valid"));
        Issued next = issue(user, current.getFamilyId());
        current.markRotated(now, next.entity().getId());
        return new Rotation(user.getId(), user.getEmail(), tokenVersion, next.issued());
    }

    /** Logout: revokes every still-active token in the presented token's family. Unknown tokens are ignored. */
    @Transactional
    public void revokeSession(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        String hash = hash(rawToken);
        lockOwner(hash);
        repository.findByTokenHashForUpdate(hash)
                .ifPresent(token -> repository.revokeFamily(token.getFamilyId(), clock.instant(),
                        RevocationReason.LOGOUT));
    }

    private RefreshToken findLockedAfterOwner(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new InvalidRefreshTokenException("Refresh token is missing");
        }
        String hash = hash(rawToken);
        lockOwner(hash);
        return repository.findByTokenHashForUpdate(hash)
                .orElseThrow(() -> new InvalidRefreshTokenException("Refresh token is not recognised"));
    }

    /**
     * Share-locks the token owner's user row before the token row is locked (lock order: user, then tokens; see
     * UserRepository). A concurrent account-status change therefore completes either entirely before this
     * transaction, whose status check then sees it, or entirely after it, and then also revokes any successor
     * issued here. The locked User entity is used only to issue the successor; status and token version are read
     * separately from the database ({@link #isActive}, {@code findTokenVersionById}) while the lock is held.
     */
    private void lockOwner(String tokenHash) {
        repository.findUserIdByTokenHash(tokenHash).ifPresent(userRepository::findByIdForShare);
    }

    private boolean isActive(Long userId) {
        return userRepository.findAccountStatusById(userId).orElse(null) == AccountStatus.ACTIVE;
    }

    private record Issued(RefreshToken entity, IssuedToken issued) {
    }

    private Issued issue(User user, UUID familyId) {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        Instant now = clock.instant();
        Instant expiresAt = now.plus(authProperties.refreshTokenTtl());
        RefreshToken saved = repository.save(new RefreshToken(user, hash(raw), familyId, now, expiresAt));
        return new Issued(saved, new IssuedToken(raw, expiresAt));
    }

    static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
