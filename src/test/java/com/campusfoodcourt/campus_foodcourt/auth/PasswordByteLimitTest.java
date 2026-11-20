package com.campusfoodcourt.campus_foodcourt.auth;

import com.campusfoodcourt.campus_foodcourt.support.AbstractAuthApiTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** BCrypt accepts at most 72 bytes; the limit is enforced on UTF-8 bytes and reported as 400, never 500. */
class PasswordByteLimitTest extends AbstractAuthApiTest {

    private static final String DEVANAGARI_A = "अ"; // 3 bytes in UTF-8
    private static final String EMOJI_LOCK = "🔒"; // U+1F512, 4 bytes in UTF-8, 2 Java chars

    @Test
    void nonAsciiPasswordOver72BytesButUnder72CharsIsRejectedWith400() throws Exception {
        String password = DEVANAGARI_A.repeat(30); // 30 chars, 90 bytes
        assertThat(bytes(password)).isEqualTo(90);
        String email = uniqueEmail();

        register("Hindi", email, password)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").value("must be at most 72 bytes when UTF-8 encoded"));
        assertThat(jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email)).isZero();
    }

    @Test
    void nonAsciiPasswordOfExactly72BytesWorksForRegisterAndLogin() throws Exception {
        String password = DEVANAGARI_A.repeat(24); // 72 bytes
        assertThat(bytes(password)).isEqualTo(72);
        String email = uniqueEmail();

        register("Hindi", email, password).andExpect(status().isCreated());
        login(email, password).andExpect(status().isOk());
        login(email, DEVANAGARI_A.repeat(23) + "a").andExpect(status().isUnauthorized());
    }

    @Test
    void fourByteCharactersAreCountedAsFourBytes() throws Exception {
        register("Emoji", uniqueEmail(), EMOJI_LOCK.repeat(18)).andExpect(status().isCreated());      // 72 bytes
        register("Emoji", uniqueEmail(), EMOJI_LOCK.repeat(19)).andExpect(status().isBadRequest());   // 76 bytes
    }

    @Test
    void asciiBoundaryIs72Bytes() throws Exception {
        register("Ascii", uniqueEmail(), "a".repeat(72)).andExpect(status().isCreated());
        register("Ascii", uniqueEmail(), "a".repeat(73)).andExpect(status().isBadRequest());
    }

    @Test
    void loginWithOversizedPasswordIsAPlain401NotA500() throws Exception {
        String email = uniqueEmail();
        register("User", email, PASSWORD).andExpect(status().isCreated());

        login(email, DEVANAGARI_A.repeat(30)).andExpect(status().isUnauthorized());
        login(uniqueEmail(), DEVANAGARI_A.repeat(30)).andExpect(status().isUnauthorized());
    }

    private static int bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }
}
