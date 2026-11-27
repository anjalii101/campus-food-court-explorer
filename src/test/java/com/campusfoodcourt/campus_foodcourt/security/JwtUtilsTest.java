package com.campusfoodcourt.campus_foodcourt.security;

import com.campusfoodcourt.campus_foodcourt.auth.JwtProperties;
import com.campusfoodcourt.campus_foodcourt.support.MutableClock;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.IncorrectClaimException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.MissingClaimException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.MacAlgorithm;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests of access-token generation and validation, without a Spring context. A 64-byte key is used so
 * that HS384/HS512 tokens can be signed with the very same key bytes, proving the HS256-only policy is
 * really enforced and not just a side effect of key length.
 */
class JwtUtilsTest {

    private static final byte[] KEY_BYTES = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
            .getBytes(StandardCharsets.US_ASCII); // 64 bytes
    private static final String ISSUER = "campus-foodcourt";
    private static final long FIFTEEN_MINUTES_MS = 15 * 60 * 1000L;
    private static final String EMAIL = "student@campus.test";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-27T10:00:00Z"));
    private final JwtUtils jwtUtils = jwtUtils(KEY_BYTES, clock);
    private final SecretKey key = Keys.hmacShaKeyFor(KEY_BYTES);

    // ------------------------------------------------------------------ valid tokens

    @Test
    void generatedTokenIsHs256WithIssuerTypeSubjectAndFifteenMinuteLifetime() {
        String token = jwtUtils.generateToken(EMAIL, 0);

        var jws = Jwts.parser().verifyWith(key).clock(this::now).build().parseSignedClaims(token);
        assertThat(jws.getHeader().getAlgorithm()).isEqualTo("HS256");
        Claims claims = jws.getPayload();
        assertThat(claims.getSubject()).isEqualTo(EMAIL);
        assertThat(claims.getIssuer()).isEqualTo(ISSUER);
        assertThat(claims.get(JwtUtils.TOKEN_TYPE_CLAIM, String.class)).isEqualTo(JwtUtils.ACCESS_TOKEN_TYPE);
        assertThat(claims.getIssuedAt().toInstant()).isEqualTo(clock.instant());
        assertThat(claims.getExpiration().getTime() - claims.getIssuedAt().getTime()).isEqualTo(FIFTEEN_MINUTES_MS);
    }

    @Test
    void validTokenYieldsTheEmail() {
        assertThat(jwtUtils.extractVerifiedEmail(jwtUtils.generateToken(EMAIL, 0))).isEqualTo(EMAIL);
    }

    @ParameterizedTest(name = "{0}-byte key")
    @ValueSource(ints = {32, 48, 64})
    void anyAllowedKeyLengthSignsAndVerifiesWithHs256(int keyLength) {
        // Keys.hmacShaKeyFor names a 48-byte key "HmacSHA384" and a 64-byte key "HmacSHA512";
        // HS256 must still be used for signing (the .env.example suggests 48 bytes).
        JwtUtils utils = jwtUtils(java.util.Arrays.copyOf(KEY_BYTES, keyLength), clock);
        String token = utils.generateToken(EMAIL, 0);
        assertThat(header(token)).contains("\"alg\":\"HS256\"");
        assertThat(utils.extractVerifiedEmail(token)).isEqualTo(EMAIL);
    }

    // ------------------------------------------------------------------ signature / key

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        byte[] otherKey = "another-key-of-thirty-two-bytes!another-key-of-thirty-two-bytes!"
                .getBytes(StandardCharsets.US_ASCII);
        String foreign = jwtUtils(otherKey, clock).generateToken(EMAIL, 0);
        assertThatThrownBy(() -> jwtUtils.extractVerifiedEmail(foreign)).isInstanceOf(SignatureException.class);
    }

    @Test
    void tamperedPayloadIsRejected() {
        String[] parts = jwtUtils.generateToken(EMAIL, 0).split("\\.");
        String forgedPayload = b64url("{\"iss\":\"campus-foodcourt\",\"token_type\":\"access\","
                + "\"sub\":\"admin@campus.test\",\"exp\":" + (clock.instant().getEpochSecond() + 600) + "}");
        String tampered = parts[0] + "." + forgedPayload + "." + parts[2];
        assertThatThrownBy(() -> jwtUtils.extractVerifiedEmail(tampered)).isInstanceOf(SignatureException.class);
    }

    // ------------------------------------------------------------------ expiry

    @Test
    void tokenIsValidJustBeforeAndRejectedJustAfterItsExpiry() {
        String token = jwtUtils.generateToken(EMAIL, 0);

        clock.advance(Duration.ofMinutes(15).minusSeconds(1)); // 1 s before exp
        assertThat(jwtUtils.extractVerifiedEmail(token)).isEqualTo(EMAIL);

        clock.advance(Duration.ofSeconds(2)); // 1 s after exp (no clock-skew allowance is configured)
        assertThatThrownBy(() -> jwtUtils.extractVerifiedEmail(token)).isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void tokenWithoutExpirationIsRejected() {
        String token = sign(b -> b.issuer(ISSUER).subject(EMAIL).claim(JwtUtils.TOKEN_TYPE_CLAIM, "access"));
        assertThatThrownBy(() -> jwtUtils.extractVerifiedEmail(token))
                .isInstanceOf(MalformedJwtException.class).hasMessageContaining("no expiration");
    }

    // ------------------------------------------------------------------ issuer

    @Test
    void wrongIssuerIsRejected() {
        String token = sign(b -> valid(b).issuer("some-other-service"));
        assertThatThrownBy(() -> jwtUtils.extractVerifiedEmail(token)).isInstanceOf(IncorrectClaimException.class);
    }

    @Test
    void missingIssuerIsRejected() {
        String token = sign(b -> b.subject(EMAIL).claim(JwtUtils.TOKEN_TYPE_CLAIM, "access").expiration(inMinutes(5)));
        assertThatThrownBy(() -> jwtUtils.extractVerifiedEmail(token)).isInstanceOf(MissingClaimException.class);
    }

    @Test
    void issuerFromConfigurationIsUsedForGenerationAndValidation() {
        JwtUtils otherIssuer = new JwtUtils(new JwtProperties(base64(KEY_BYTES), FIFTEEN_MINUTES_MS, "other"), clock);
        String token = otherIssuer.generateToken(EMAIL, 0);
        assertThat(otherIssuer.extractVerifiedEmail(token)).isEqualTo(EMAIL);
        assertThatThrownBy(() -> jwtUtils.extractVerifiedEmail(token)).isInstanceOf(IncorrectClaimException.class);
    }

    // ------------------------------------------------------------------ token type

    @Test
    void wrongTokenTypeIsRejected() {
        String token = sign(b -> valid(b).claim(JwtUtils.TOKEN_TYPE_CLAIM, "refresh"));
        assertThatThrownBy(() -> jwtUtils.extractVerifiedEmail(token)).isInstanceOf(IncorrectClaimException.class);
    }

    @Test
    void missingTokenTypeIsRejected() {
        String token = sign(b -> b.issuer(ISSUER).subject(EMAIL).expiration(inMinutes(5)));
        assertThatThrownBy(() -> jwtUtils.extractVerifiedEmail(token)).isInstanceOf(MissingClaimException.class);
    }

    @Test
    void missingSubjectIsRejected() {
        String token = sign(b -> b.issuer(ISSUER).claim(JwtUtils.TOKEN_TYPE_CLAIM, "access").expiration(inMinutes(5)));
        assertThatThrownBy(() -> jwtUtils.extractVerifiedEmail(token))
                .isInstanceOf(MalformedJwtException.class).hasMessageContaining("no subject");
    }

    // ------------------------------------------------------------------ malformed

    @ParameterizedTest
    @ValueSource(strings = {"garbage", "a.b.c", "a.b", "....", "eyJhbGciOiJIUzI1NiJ9.e30.", "not.base64!.at-all"})
    void malformedTokensAreRejectedWithAJwtException(String bad) {
        assertThatThrownBy(() -> jwtUtils.extractVerifiedEmail(bad)).isInstanceOf(JwtException.class);
    }

    @Test
    void emptyTokenIsRejected() {
        assertThatThrownBy(() -> jwtUtils.extractVerifiedEmail("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jwtUtils.extractVerifiedEmail(null)).isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ algorithm

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"HS384", "HS512"})
    void otherHmacAlgorithmWithTheSameKeyIsRejected(String algorithm) {
        MacAlgorithm alg = "HS384".equals(algorithm) ? Jwts.SIG.HS384 : Jwts.SIG.HS512;
        String token = Jwts.builder().issuer(ISSUER).subject(EMAIL).claim(JwtUtils.TOKEN_TYPE_CLAIM, "access")
                .expiration(inMinutes(5)).signWith(key, alg).compact();

        // Negative control: a default (permissive) parser accepts this token with our key ...
        assertThatCode(() -> Jwts.parser().verifyWith(key).clock(this::now).build().parseSignedClaims(token))
                .doesNotThrowAnyException();
        // ... the application parser does not.
        assertThatThrownBy(() -> jwtUtils.extractVerifiedEmail(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void unsignedAlgNoneTokenIsRejected() {
        String unsigned = Jwts.builder().issuer(ISSUER).subject(EMAIL).claim(JwtUtils.TOKEN_TYPE_CLAIM, "access")
                .expiration(inMinutes(5)).compact();
        assertThat(header(unsigned)).contains("\"alg\":\"none\"");
        assertThatThrownBy(() -> jwtUtils.extractVerifiedEmail(unsigned)).isInstanceOf(UnsupportedJwtException.class);
    }

    @Test
    void handCraftedAlgNoneWithStrippedSignatureIsRejected() {
        String[] parts = jwtUtils.generateToken(EMAIL, 0).split("\\.");
        String noneToken = b64url("{\"alg\":\"none\"}") + "." + parts[1] + ".";
        assertThatThrownBy(() -> jwtUtils.extractVerifiedEmail(noneToken)).isInstanceOf(JwtException.class);
    }

    @Test
    void headerClaimingHs512OnAnHs256SignatureIsRejected() {
        String[] parts = jwtUtils.generateToken(EMAIL, 0).split("\\.");
        String relabelled = b64url("{\"alg\":\"HS512\"}") + "." + parts[1] + "." + parts[2];
        assertThatThrownBy(() -> jwtUtils.extractVerifiedEmail(relabelled)).isInstanceOf(JwtException.class);
    }

    // ------------------------------------------------------------------ token version

    @ParameterizedTest(name = "version {0}")
    @ValueSource(longs = {0, 1, 7, Integer.MAX_VALUE + 1L, Long.MAX_VALUE})
    void tokenVersionIsIssuedAndReturnedExactly(long version) {
        String token = jwtUtils.generateToken(EMAIL, version);
        assertThat(jwtUtils.verify(token)).isEqualTo(new JwtUtils.VerifiedAccessToken(EMAIL, version));
    }

    @Test
    void handSignedTokenWithAllRequiredClaimsIsAccepted() {
        // Positive control for the hand-signed negative tests: valid() alone passes.
        assertThat(jwtUtils.verify(sign(this::valid))).isEqualTo(new JwtUtils.VerifiedAccessToken(EMAIL, 0));
    }

    @Test
    void tokenWithoutVersionIsRejected() {
        // e.g. an access token issued before the token version existed: refused, so the client refreshes.
        String token = sign(this::validWithoutVersion);
        assertThatThrownBy(() -> jwtUtils.verify(token))
                .isInstanceOf(MalformedJwtException.class).hasMessageContaining("token version");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"negative", "fraction", "text", "boolean"})
    void tokenWithInvalidVersionIsRejected(String kind) {
        Object value = switch (kind) {
            case "negative" -> -1;
            case "fraction" -> 1.5;
            case "text" -> "1";
            default -> true;
        };
        String token = sign(b -> validWithoutVersion(b).claim(JwtUtils.TOKEN_VERSION_CLAIM, value));
        assertThatThrownBy(() -> jwtUtils.verify(token))
                .isInstanceOf(MalformedJwtException.class).hasMessageContaining("token version");
    }

    @Test
    void negativeVersionCannotBeIssued() {
        assertThatThrownBy(() -> jwtUtils.generateToken(EMAIL, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ helpers

    private static JwtUtils jwtUtils(byte[] keyBytes, MutableClock clock) {
        return new JwtUtils(new JwtProperties(base64(keyBytes), FIFTEEN_MINUTES_MS, ISSUER), clock);
    }

    private io.jsonwebtoken.JwtBuilder valid(io.jsonwebtoken.JwtBuilder b) {
        return b.issuer(ISSUER).subject(EMAIL).claim(JwtUtils.TOKEN_TYPE_CLAIM, "access")
                .claim(JwtUtils.TOKEN_VERSION_CLAIM, 0).expiration(inMinutes(5));
    }

    /** Like {@link #valid} but without the token-version claim. */
    private io.jsonwebtoken.JwtBuilder validWithoutVersion(io.jsonwebtoken.JwtBuilder b) {
        return b.issuer(ISSUER).subject(EMAIL).claim(JwtUtils.TOKEN_TYPE_CLAIM, "access").expiration(inMinutes(5));
    }

    private String sign(Consumer<io.jsonwebtoken.JwtBuilder> claims) {
        io.jsonwebtoken.JwtBuilder builder = Jwts.builder();
        claims.accept(builder);
        return builder.signWith(key, Jwts.SIG.HS256).compact();
    }

    private Date inMinutes(int minutes) {
        return Date.from(clock.instant().plus(Duration.ofMinutes(minutes)));
    }

    private Date now() {
        return Date.from(clock.instant());
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String b64url(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String header(String token) {
        return new String(Base64.getUrlDecoder().decode(token.split("\\.")[0]), StandardCharsets.UTF_8);
    }
}
