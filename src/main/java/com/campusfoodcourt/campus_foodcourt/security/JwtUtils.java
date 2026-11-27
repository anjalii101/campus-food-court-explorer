package com.campusfoodcourt.campus_foodcourt.security;

import com.campusfoodcourt.campus_foodcourt.auth.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
@Component
public class JwtUtils {
    /** Name of the claim that states what the token is for (see docs/authentication.md). */
    public static final String TOKEN_TYPE_CLAIM = "token_type";
    /** The only token type this class issues or accepts. */
    public static final String ACCESS_TOKEN_TYPE = "access";
    /** Claim holding the user's token version at issue time (users.token_version, V9 migration). */
    public static final String TOKEN_VERSION_CLAIM = "token_version";

    private final SecretKey signingKey;
    private final String issuer;
    private final long expirationMs;
    private final Clock clock;
    private final JwtParser parser;

    /** All settings come from the validated {@link JwtProperties}; there are no fallback values here. */
    public JwtUtils(JwtProperties properties, Clock clock) {
        this.signingKey = Keys.hmacShaKeyFor(properties.decodedSecret());
        this.issuer = properties.issuer();
        this.expirationMs = properties.expirationMs();
        this.clock = clock;
        this.parser = Jwts.parser()
                // Accept HS256 only. Without this a token signed with the same key using HS384/HS512
                // (possible once the key is >= 48/64 bytes) would verify; "none" and JWE are refused as well.
                .sig().clear().add(Jwts.SIG.HS256).and()
                .verifyWith(signingKey)
                .requireIssuer(issuer)
                .require(TOKEN_TYPE_CLAIM, ACCESS_TOKEN_TYPE)
                .clock(() -> Date.from(clock.instant()))
                .build();
    }

    /**
     * Issues a signed HS256 access token whose subject is the user's email. {@code tokenVersion} is the user's
     * current users.token_version; the token is accepted only while that version is unchanged.
     */
    public String generateToken(String email, long tokenVersion) {
        if (tokenVersion < 0) {
            throw new IllegalArgumentException("tokenVersion must not be negative");
        }
        Instant now = clock.instant();
        return Jwts.builder()
                .issuer(issuer)
                .subject(email)
                .claim(TOKEN_TYPE_CLAIM, ACCESS_TOKEN_TYPE)
                .claim(TOKEN_VERSION_CLAIM, tokenVersion)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expirationMs)))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    /** The verified contents the application uses: who the token is for and which token version it carries. */
    public record VerifiedAccessToken(String email, long tokenVersion) {
    }

    /**
     * Verifies the token completely. Signature, algorithm (HS256 only), expiry, issuer and token type are checked,
     * and subject, expiry and a non-negative integer token version must be present. Whether that version is still
     * current is decided by the caller against the user's server-side state (JwtAuthenticationFilter).
     *
     * @throws JwtException             if the token is malformed, unsigned, signed with another key or algorithm,
     *                                  expired, or carries the wrong issuer/token type or no subject/expiry/version
     * @throws IllegalArgumentException if the token is null or empty
     */
    public VerifiedAccessToken verify(String token) {
        Claims claims = parser.parseSignedClaims(token).getPayload();
        if (claims.getExpiration() == null) {
            throw new MalformedJwtException("Access token has no expiration");
        }
        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new MalformedJwtException("Access token has no subject");
        }
        // Integer types only: a fractional or textual value is rejected rather than rounded.
        Object version = claims.get(TOKEN_VERSION_CLAIM);
        if (!(version instanceof Integer || version instanceof Long) || ((Number) version).longValue() < 0) {
            throw new MalformedJwtException("Access token has no valid token version");
        }
        return new VerifiedAccessToken(subject, ((Number) version).longValue());
    }

    /** {@link #verify} and return only the subject (the user's email). */
    public String extractVerifiedEmail(String token) {
        return verify(token).email();
    }
}
