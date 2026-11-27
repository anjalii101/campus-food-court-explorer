package com.campusfoodcourt.campus_foodcourt.auth;

import com.campusfoodcourt.campus_foodcourt.support.AbstractAuthApiTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Logout and theft are recorded differently; only replay of a rotated token raises the theft warning. */
@ExtendWith(OutputCaptureExtension.class)
class RevocationReasonTest extends AbstractAuthApiTest {

    private static final String THEFT_WARNING = "Refresh token reuse detected";

    @Test
    void logoutRecordsLogoutReasonAndReplayAfterLogoutRaisesNoTheftAlert(CapturedOutput output) throws Exception {
        String t1 = newSession().refreshToken();
        String t2 = cookieValue(refresh(t1).andExpect(status().isOk()).andReturn(), REFRESH_COOKIE);

        logout(t2).andExpect(status().isNoContent());
        refresh(t2).andExpect(status().isUnauthorized()); // e.g. a stale tab retrying after logout

        assertThat(reasons(t1)).containsExactly("ROTATED", "LOGOUT");
        assertThat(output.getAll()).doesNotContain(THEFT_WARNING);
    }

    @Test
    void replayOfRotatedTokenRecordsReuseAndRaisesTheftAlert(CapturedOutput output) throws Exception {
        String t1 = newSession().refreshToken();
        refresh(t1).andExpect(status().isOk());

        refresh(t1).andExpect(status().isUnauthorized());

        assertThat(reasons(t1)).containsExactly("ROTATED", "REUSE_DETECTED");
        assertThat(output.getAll()).contains(THEFT_WARNING);
    }

    @Test
    void revokedTimestampAndReasonAreAlwaysSetTogether() throws Exception {
        String t1 = newSession().refreshToken();
        refresh(t1);
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens "
                + "where (revoked_at is null) <> (revoked_reason is null)", Integer.class)).isZero();
    }

    private List<String> reasons(String anyTokenOfFamily) {
        UUID family = jdbc.queryForObject("select family_id from refresh_tokens where token_hash = ?", UUID.class,
                RefreshTokenService.hash(anyTokenOfFamily));
        return jdbc.queryForList("select revoked_reason from refresh_tokens where family_id = ? order by id",
                String.class, family);
    }
}
