package com.campusfoodcourt.campus_foodcourt.auth;

import com.campusfoodcourt.campus_foodcourt.security.JwtUtils;
import com.campusfoodcourt.campus_foodcourt.support.AbstractAuthApiTest;
import com.fasterxml.jackson.databind.JsonNode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Access tokens end to end: HS256, signed with the configured key, issuer and token type claims, 15-minute
 * lifetime; anything else is refused by the bearer filter with 401.
 */
class JwtAccessTokenTest extends AbstractAuthApiTest {

    @Autowired
    private JwtUtils jwtUtils;
    @Autowired
    private JwtProperties jwtProperties;

    @Test
    void defaultsAreFifteenMinutesAndTheApplicationIssuer() {
        assertThat(jwtProperties.expirationMs()).isEqualTo(15 * 60 * 1000L);
        assertThat(jwtProperties.issuer()).isEqualTo("campus-foodcourt");
    }

    @Test
    void issuedTokenCarriesTheDocumentedHeaderAndClaims() throws Exception {
        Session session = newSession();
        String token = session.accessToken();

        JsonNode header = objectMapper.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[0]));
        assertThat(header.path("alg").asText()).isEqualTo("HS256");

        Claims claims = Jwts.parser().verifyWith(key()).build().parseSignedClaims(token).getPayload();
        assertThat(claims.getSubject()).isEqualTo(session.email());
        assertThat(claims.getIssuer()).isEqualTo("campus-foodcourt");
        assertThat(claims.get("token_type", String.class)).isEqualTo("access");
        long lifetimeSeconds = (claims.getExpiration().getTime() - claims.getIssuedAt().getTime()) / 1000;
        assertThat(lifetimeSeconds).isEqualTo(900);
        assertThat(claims.getIssuedAt()).isCloseTo(new Date(), 60_000);

        assertThat(jwtUtils.extractVerifiedEmail(token)).isEqualTo(session.email());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(session.email()));
    }

    @Test
    void tokenDoesNotVerifyWithAnotherKey() throws Exception {
        String token = newSession().accessToken();
        SecretKey otherKey = Keys.hmacShaKeyFor("a-completely-different-key-of-32+-bytes!".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> Jwts.parser().verifyWith(otherKey).build().parseSignedClaims(token))
                .isInstanceOf(SignatureException.class);
    }

    @Test
    void tokenStillValidJustBeforeExpiryAndRejectedAfter() throws Exception {
        Session session = newSession();

        String almostExpired = sign(b -> claims(b, session.email(), -899_000, 1_000));   // issued 14:59 ago, 1s left
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + almostExpired))
                .andExpect(status().isOk());

        String expired = sign(b -> claims(b, session.email(), -901_000, -1_000));        // issued 15:01 ago
        assertThatThrownBy(() -> jwtUtils.extractVerifiedEmail(expired)).isInstanceOf(ExpiredJwtException.class);
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedTamperedForeignUnsignedMisissuedMistypedAndWrongAlgorithmTokensAreRejected() throws Exception {
        Session session = newSession();
        String email = session.email();
        String[] parts = session.accessToken().split("\\.");
        long exp = System.currentTimeMillis() / 1000 + 600;

        String tamperedPayload = parts[0] + "." + b64url("{\"iss\":\"campus-foodcourt\",\"token_type\":\"access\","
                + "\"sub\":\"admin@campus.test\",\"exp\":" + exp + "}") + "." + parts[2];
        String foreignKey = Jwts.builder().issuer("campus-foodcourt").claim("token_type", "access").subject(email)
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(new byte[32]), Jwts.SIG.HS256).compact();
        String unsigned = Jwts.builder().issuer("campus-foodcourt").claim("token_type", "access").subject(email)
                .expiration(new Date(System.currentTimeMillis() + 60_000)).compact(); // alg "none"
        String wrongIssuer = sign(b -> claims(b, email, 0, 60_000).issuer("another-app"));
        String noIssuer = sign(b -> b.subject(email).claim("token_type", "access")
                .expiration(new Date(System.currentTimeMillis() + 60_000)));
        String wrongType = sign(b -> claims(b, email, 0, 60_000).claim("token_type", "refresh"));
        String noType = sign(b -> b.issuer("campus-foodcourt").subject(email)
                .expiration(new Date(System.currentTimeMillis() + 60_000)));
        // The test key decodes to 53 bytes, enough for HS384: same key bytes, different algorithm.
        String hs384 = claims(Jwts.builder(), email, 0, 60_000).signWith(key(), Jwts.SIG.HS384).compact();

        for (String bad : new String[]{"garbage", tamperedPayload, foreignKey, unsigned, wrongIssuer, noIssuer,
                wrongType, noType, hs384}) {
            mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + bad))
                    .andExpect(status().isUnauthorized());
        }
    }

    private SecretKey key() {
        return Keys.hmacShaKeyFor(jwtProperties.decodedSecret());
    }

    private static JwtBuilder claims(JwtBuilder builder, String subject, long issuedOffsetMs, long expiryOffsetMs) {
        long now = System.currentTimeMillis();
        return builder.issuer("campus-foodcourt").claim("token_type", "access").subject(subject)
                .issuedAt(new Date(now + issuedOffsetMs))
                .expiration(new Date(now + expiryOffsetMs));
    }

    private String sign(UnaryOperator<JwtBuilder> claims) {
        return claims.apply(Jwts.builder()).signWith(key(), Jwts.SIG.HS256).compact();
    }

    private static String b64url(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
