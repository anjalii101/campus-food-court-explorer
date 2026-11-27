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

    /** Issues a signed HS256 access token whose subject is the user's email. */
    public String generateToken(String email) {
        Instant now = clock.instant();
        return Jwts.builder()
                .issuer(issuer)
                .subject(email)
                .claim(TOKEN_TYPE_CLAIM, ACCESS_TOKEN_TYPE)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expirationMs)))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Verifies the token completely and returns its subject (the user's email).
     * Signature, algorithm (HS256 only), expiry, issuer and token type are all checked.
     *
     * @throws JwtException             if the token is malformed, unsigned, signed with another key or algorithm,
     *                                  expired, or carries the wrong issuer/token type or no subject/expiry
     * @throws IllegalArgumentException if the token is null or empty
     */
    public String extractVerifiedEmail(String token) {
        Claims claims = parser.parseSignedClaims(token).getPayload();
        if (claims.getExpiration() == null) {
            throw new MalformedJwtException("Access token has no expiration");
        }
        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new MalformedJwtException("Access token has no subject");
        }
        return subject;
    }
}
