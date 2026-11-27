package com.campusfoodcourt.campus_foodcourt.service;

import com.campusfoodcourt.campus_foodcourt.auth.InvalidRefreshTokenException;
import com.campusfoodcourt.campus_foodcourt.auth.JwtProperties;
import com.campusfoodcourt.campus_foodcourt.auth.RefreshTokenService;
import com.campusfoodcourt.campus_foodcourt.dto.AuthRequest;
import com.campusfoodcourt.campus_foodcourt.dto.AuthResponse;
import com.campusfoodcourt.campus_foodcourt.entity.AccountStatus;
import com.campusfoodcourt.campus_foodcourt.entity.User;
import com.campusfoodcourt.campus_foodcourt.exception.EmailAlreadyInUseException;
import com.campusfoodcourt.campus_foodcourt.exception.ResourceNotFoundException;
import com.campusfoodcourt.campus_foodcourt.repository.UserRepository;
import com.campusfoodcourt.campus_foodcourt.security.JwtUtils;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;
import java.util.Optional;

/**
 * Registration, login, refresh and logout.
 * <p>
 * Transaction boundaries are explicit (TransactionTemplate) so that:
 * <ul>
 *   <li>BCrypt hashing/verification runs <b>outside</b> any transaction and never holds a pooled connection;</li>
 *   <li>persisting the user/refresh token and minting the access token happen in <b>one</b> transaction -
 *       if minting fails, nothing is committed (no orphaned user, no revoked-but-unreplaced refresh token).</li>
 * </ul>
 */
@Service
public class AuthService {
    /** Name of the case-insensitive unique index on users.email (V1 migration). */
    static final String EMAIL_UNIQUE_INDEX = "uk_users_email_lower";

    private static final String TOKEN_TYPE = "Bearer";
    private static final String DEFAULT_ROLE = "ROLE_USER";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final RefreshTokenService refreshTokenService;
    private final TransactionTemplate transactionTemplate;
    private final long accessTokenTtlSeconds;
    /** Compared against when the email is unknown, so both failure paths cost one BCrypt check. */
    private final String dummyPasswordHash;


    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtUtils jwtUtils,
                       RefreshTokenService refreshTokenService, JwtProperties jwtProperties,
                       TransactionTemplate transactionTemplate) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtils = jwtUtils;
        this.refreshTokenService = refreshTokenService;
        this.transactionTemplate = transactionTemplate;
        this.accessTokenTtlSeconds = jwtProperties.expirationMs() / 1000;
        this.dummyPasswordHash = passwordEncoder.encode("timing-equalisation-dummy-password");
    }


    public AuthResponse register(User user) {
        user.setEmail(normaliseEmail(user.getEmail()));
        if (userRepository.findByEmail(user.getEmail()).isPresent()) {   // short read, connection released
            throw new EmailAlreadyInUseException();
        }
        user.setPassword(passwordEncoder.encode(user.getPassword()));  // BCrypt: no transaction/connection held
        user.setRole(DEFAULT_ROLE);
        user.setAccountStatus(AccountStatus.ACTIVE);

        return transactionTemplate.execute(status -> {
            User saved;
            try {
                // Flush now so a concurrent registration of the same email hits the unique index here.
                saved = userRepository.saveAndFlush(user);
            } catch (DataIntegrityViolationException e) {
                if (isEmailUniqueViolation(e)) {
                    throw new EmailAlreadyInUseException();
                }
                throw e;
            }
            return issueTokens(saved, saved.getTokenVersion()); // a new user starts at version 0
        });
    }


    public AuthResponse login(AuthRequest req) {
        Optional<User> found = userRepository.findByEmail(normaliseEmail(req.getEmail())); // short read
        String hash = found.map(User::getPassword).orElse(dummyPasswordHash);
        boolean passwordMatches = passwordEncoder.matches(req.getPassword(), hash);        // no connection held
        // Inactive accounts get exactly the same failure as a wrong password (and only after the BCrypt check),
        // so the response reveals nothing about the account's status.
        if (found.isEmpty() || !passwordMatches || !found.get().isActive()) {
            throw new BadCredentialsException("Invalid credentials");
        }
        return transactionTemplate.execute(status -> {
            // Re-checked under a share lock on the user row (lock order: user, then tokens): a concurrent
            // deactivation either commits first, and this login is refused, or waits for this transaction and
            // then revokes the session created here.
            Long userId = found.get().getId();
            User user = userRepository.findByIdForShare(userId)
                    .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
            // Status read from the database under the lock, not from entity state (see RefreshTokenService).
            if (userRepository.findAccountStatusById(userId).orElse(null) != AccountStatus.ACTIVE) {
                throw new BadCredentialsException("Invalid credentials");
            }
            // Likewise the token version: a deactivation cannot change it while this lock is held.
            long tokenVersion = userRepository.findTokenVersionById(userId)
                    .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
            return issueTokens(user, tokenVersion);
        });
    }


    /**
     * Exchanges a refresh token for a new access token and a new (rotated) refresh token.
     * Rotation and access-token minting share one transaction: if minting fails, the rotation is rolled
     * back and the presented token stays valid. A rejected token (including reuse detection, whose family
     * revocation must persist) is committed first and then reported.
     */
    public AuthResponse refresh(String refreshToken) {
        RefreshOutcome outcome = transactionTemplate.execute(status -> {
            try {
                RefreshTokenService.Rotation rotation = refreshTokenService.rotate(refreshToken);
                String accessToken = jwtUtils.generateToken(rotation.email(), rotation.tokenVersion());
                return RefreshOutcome.success(new AuthResponse(accessToken, TOKEN_TYPE, accessTokenTtlSeconds,
                        rotation.next().rawToken(), rotation.next().expiresAt()));
            } catch (InvalidRefreshTokenException e) {
                return RefreshOutcome.rejected(e);
            }
        });
        if (outcome.rejection() != null) {
            throw outcome.rejection();
        }
        return outcome.response();
    }


    /** Ends the session the refresh token belongs to. Already-issued access tokens expire on their own. */
    public void logout(String refreshToken) {
        refreshTokenService.revokeSession(refreshToken);
    }


    @Transactional(readOnly = true)
    public User getByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }


    /** Must run inside a transaction; the access token is minted before commit. */
    private AuthResponse issueTokens(User user, long tokenVersion) {
        RefreshTokenService.IssuedToken refresh = refreshTokenService.issueForNewSession(user);
        String accessToken = jwtUtils.generateToken(user.getEmail(), tokenVersion);
        return new AuthResponse(accessToken, TOKEN_TYPE, accessTokenTtlSeconds, refresh.rawToken(),
                refresh.expiresAt());
    }


    static boolean isEmailUniqueViolation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve) {
                return EMAIL_UNIQUE_INDEX.equalsIgnoreCase(cve.getConstraintName());
            }
        }
        return false;
    }


    private static String normaliseEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }


    private record RefreshOutcome(AuthResponse response, InvalidRefreshTokenException rejection) {
        static RefreshOutcome success(AuthResponse response) {
            return new RefreshOutcome(response, null);
        }

        static RefreshOutcome rejected(InvalidRefreshTokenException e) {
            return new RefreshOutcome(null, e);
        }
    }
}
