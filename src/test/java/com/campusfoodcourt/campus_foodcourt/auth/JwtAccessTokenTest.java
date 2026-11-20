package com.campusfoodcourt.campus_foodcourt.auth;

import com.campusfoodcourt.campus_foodcourt.security.JwtUtils;
import com.campusfoodcourt.campus_foodcourt.support.AbstractAuthApiTest;
import com.fasterxml.jackson.databind.JsonNode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Access tokens: HS256, signed with the configured key, 15-minute lifetime, rejected once expired. */
class JwtAccessTokenTest extends AbstractAuthApiTest {

    @Autowired
    private JwtUtils jwtUtils;
    @Autowired
    private JwtProperties jwtProperties;
    @Value("${app.jwt.secret}")
    private String secret;

    @Test
    void defaultLifetimeIsFifteenMinutes() {
        assertThat(jwtProperties.expirationMs()).isEqualTo(15 * 60 * 1000L);
    }

    @Test
    void issuedTokenIsHs256SignedWithConfiguredKeyAndExpiresAfterFifteenMinutes() throws Exception {
        Session session = newSession();
        String token = session.accessToken();

        JsonNode header = objectMapper.readTree(
                Base64.getUrlDecoder().decode(token.split("\\.")[0]));
        assertThat(header.path("alg").asText()).isEqualTo("HS256");

        Claims claims = Jwts.parserBuilder().setSigningKey(key()).build().parseClaimsJws(token).getBody();
        assertThat(claims.getSubject()).isEqualTo(session.email());
        long lifetimeSeconds = (claims.getExpiration().getTime() - claims.getIssuedAt().getTime()) / 1000;
        assertThat(lifetimeSeconds).isEqualTo(900);
        assertThat(claims.getIssuedAt()).isCloseTo(new Date(), 60_000);

        assertThat(jwtUtils.validateToken(token, session.email())).isTrue();
        assertThat(jwtUtils.validateToken(token, "someone-else@campus.test")).isFalse();
    }

    @Test
    void tokenDoesNotVerifyWithAnotherKey() throws Exception {
        String token = newSession().accessToken();
        SecretKey otherKey = Keys.hmacShaKeyFor("a-completely-different-key-of-32+-bytes!".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> Jwts.parserBuilder().setSigningKey(otherKey).build().parseClaimsJws(token))
                .isInstanceOf(SignatureException.class);
    }

    @Test
    void tokenStillValidJustBeforeExpiryAndRejectedAfter() throws Exception {
        Session session = newSession();

        String almostExpired = sign(session.email(), -899_000, 1_000);   // issued 14:59 ago, 1s left
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + almostExpired))
                .andExpect(status().isOk());

        String expired = sign(session.email(), -901_000, -1_000);        // issued 15:01 ago
        assertThatThrownBy(() -> jwtUtils.extractEmail(expired)).isInstanceOf(ExpiredJwtException.class);
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedTamperedForeignAndUnsignedTokensAreRejected() throws Exception {
        Session session = newSession();
        String token = session.accessToken();
        String[] parts = token.split("\\.");

        String tamperedPayload = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"admin@campus.test\",\"exp\":" + (System.currentTimeMillis() / 1000 + 600) + "}")
                        .getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        String foreignKey = Jwts.builder().setSubject(session.email())
                .setExpiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(new byte[32]), SignatureAlgorithm.HS256).compact();
        String unsigned = Jwts.builder().setSubject(session.email())
                .setExpiration(new Date(System.currentTimeMillis() + 60_000)).compact(); // alg "none"

        for (String bad : new String[]{"garbage", tamperedPayload, foreignKey, unsigned}) {
            mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + bad))
                    .andExpect(status().isUnauthorized());
        }
    }

    private SecretKey key() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
    }

    private String sign(String subject, long issuedOffsetMs, long expiryOffsetMs) {
        long now = System.currentTimeMillis();
        return Jwts.builder().setSubject(subject)
                .setIssuedAt(new Date(now + issuedOffsetMs))
                .setExpiration(new Date(now + expiryOffsetMs))
                .signWith(key(), SignatureAlgorithm.HS256).compact();
    }
}
