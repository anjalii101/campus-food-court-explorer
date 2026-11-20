package com.campusfoodcourt.campus_foodcourt.auth;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PropertiesValidationTest {

    @Test
    void acceptsBase64SecretOf32Bytes() {
        String secret = Base64.getEncoder().encodeToString(new byte[32]);
        assertThatCode(() -> new JwtProperties(secret, 900_000)).doesNotThrowAnyException();
    }

    @Test
    void rejectsShortSecret() {
        String secret = Base64.getEncoder().encodeToString(new byte[31]);
        assertThatThrownBy(() -> new JwtProperties(secret, 900_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    void rejectsNonBase64Secret() {
        assertThatThrownBy(() -> new JwtProperties("your_super_secret_key_here!", 900_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Base64");
    }

    @Test
    void rejectsNonPositiveRefreshTtl() {
        AuthProperties.Cookie cookie = new AuthProperties.Cookie("refresh_token", "/api/auth", true, "Strict");
        assertThatThrownBy(() -> new AuthProperties(Duration.ZERO, 12, cookie))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("refresh-token-ttl");
        assertThatThrownBy(() -> new AuthProperties(Duration.ofDays(-1), 12, cookie))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("refresh-token-ttl");
        assertThatCode(() -> new AuthProperties(Duration.ofDays(7), 12, cookie)).doesNotThrowAnyException();
    }

    @Test
    void rejectsInvalidSameSiteAndInsecureSameSiteNone() {
        assertThatThrownBy(() -> new AuthProperties.Cookie("refresh_token", "/api/auth", true, "strictest"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthProperties.Cookie("refresh_token", "/api/auth", false, "None"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires");
    }
}
