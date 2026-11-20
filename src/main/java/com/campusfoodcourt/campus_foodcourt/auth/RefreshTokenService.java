package com.campusfoodcourt.campus_foodcourt.auth;

import com.campusfoodcourt.campus_foodcourt.entity.User;
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
 *       and a warning is logged. Presenting a token revoked by LOGOUT is simply rejected, without an alert.</li>
 * </ul>
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository repository;
    private final AuthProperties authProperties;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository repository, AuthProperties authProperties, Clock clock) {
        this.repository = repository;
        this.authProperties = authProperties;
        this.clock = clock;
    }

    /** A newly issued raw token. The raw value is returned to the client once and never persisted. */
    public record IssuedToken(String rawToken, Instant expiresAt) {
    }

    /** Result of a successful rotation: who the token belonged to and its successor. */
    public record Rotation(Long userId, String email, IssuedToken next) {
    }

    @Transactional
    public IssuedToken issueForNewSession(User user) {
        return issue(user, UUID.randomUUID()).issued();
    }

    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public Rotation rotate(String rawToken) {
        RefreshToken current = findLocked(rawToken);
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
        Issued next = issue(user, current.getFamilyId());
        current.markRotated(now, next.entity().getId());
        return new Rotation(user.getId(), user.getEmail(), next.issued());
    }

    /** Logout: revokes every still-active token in the presented token's family. Unknown tokens are ignored. */
    @Transactional
    public void revokeSession(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        repository.findByTokenHashForUpdate(hash(rawToken))
                .ifPresent(token -> repository.revokeFamily(token.getFamilyId(), clock.instant(),
                        RevocationReason.LOGOUT));
    }

    private RefreshToken findLocked(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new InvalidRefreshTokenException("Refresh token is missing");
        }
        return repository.findByTokenHashForUpdate(hash(rawToken))
                .orElseThrow(() -> new InvalidRefreshTokenException("Refresh token is not recognised"));
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
