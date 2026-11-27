package com.campusfoodcourt.campus_foodcourt.config;

import com.campusfoodcourt.campus_foodcourt.auth.RevocationReason;
import com.campusfoodcourt.campus_foodcourt.entity.AccountStatus;
import com.campusfoodcourt.campus_foodcourt.support.TestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V7 (users.account_status) and V8 (ACCOUNT_DEACTIVATED revocation reason) applied to a database that already
 * holds users, as an upgrade would: migrate to V6, insert users the way the pre-V7 application did, then apply the
 * rest. Embedded PostgreSQL only.
 */
class AccountStatusMigrationTest {

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateWithExistingUsers() {
        DataSource dataSource = TestDatabase.createEmptyDatabase("cfc_account_status_upgrade");
        flyway(dataSource, "6").migrate();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("insert into users (name, email, password, role) values ('Old One', 'old-1@campus.test', 'p', 'ROLE_USER')");
        jdbc.update("insert into users (name, email, password, role) values ('Old Two', 'old-2@campus.test', 'p', 'ROLE_USER')");

        assertThat(flyway(dataSource, "latest").migrate().migrations).extracting(m -> m.version)
                .containsExactly("7", "8", "9");
    }

    @Test
    void revokedReasonConstraintAllowsExactlyTheRevocationReasons() {
        Long userId = jdbc.queryForObject("select id from users where email = 'old-1@campus.test'", Long.class);
        String insert = "insert into refresh_tokens (user_id, token_hash, family_id, created_at, expires_at,"
                + " revoked_at, revoked_reason) values (?, ?, gen_random_uuid(), now(), now() + interval '1 day',"
                + " now(), ?)";
        for (RevocationReason reason : RevocationReason.values()) {
            jdbc.update(insert, userId, "hash-" + reason.name().toLowerCase(), reason.name());
        }
        assertThatThrownBy(() -> jdbc.update(insert, userId, "hash-bogus", "BOGUS"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_refresh_tokens_revoked_reason");
    }

    @Test
    void existingUsersBecomeActive() {
        assertThat(jdbc.queryForList("select account_status from users where email like 'old-%' order by email",
                String.class)).containsExactly("ACTIVE", "ACTIVE");
    }

    @Test
    void existingUsersStartAtTokenVersionZeroAndVersionsCannotBeNegative() {
        assertThat(jdbc.queryForList("select token_version from users where email like 'old-%' order by email",
                Long.class)).containsExactly(0L, 0L);
        assertThat(jdbc.queryForObject("select is_nullable from information_schema.columns"
                + " where table_name = 'users' and column_name = 'token_version'", String.class)).isEqualTo("NO");
        assertThatThrownBy(() -> jdbc.update("update users set token_version = -1 where email = 'old-1@campus.test'"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_users_token_version_non_negative");
    }

    @Test
    void columnIsNotNullWithActiveDefault() {
        assertThat(jdbc.queryForObject("select is_nullable from information_schema.columns"
                + " where table_name = 'users' and column_name = 'account_status'", String.class)).isEqualTo("NO");
        jdbc.update("insert into users (name, email, password, role) values ('New', 'new-default@campus.test', 'p', 'ROLE_USER')");
        assertThat(jdbc.queryForObject("select account_status from users where email = 'new-default@campus.test'",
                String.class)).isEqualTo("ACTIVE");

        assertThatThrownBy(() -> jdbc.update("insert into users (name, email, password, role, account_status)"
                + " values ('Null', 'null-status@campus.test', 'p', 'ROLE_USER', null)"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("account_status");
    }

    @Test
    void checkConstraintAllowsExactlyTheEnumValues() {
        List<String> enumNames = Arrays.stream(AccountStatus.values()).map(Enum::name).toList();
        for (String status : enumNames) {
            jdbc.update("insert into users (name, email, password, role, account_status) values ('S', ?, 'p', 'ROLE_USER', ?)",
                    "status-" + status.toLowerCase() + "@campus.test", status);
        }
        assertThatThrownBy(() -> jdbc.update("insert into users (name, email, password, role, account_status)"
                + " values ('Bad', 'bad-status@campus.test', 'p', 'ROLE_USER', 'BANNED')"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_users_account_status");
        assertThatThrownBy(() -> jdbc.update("insert into users (name, email, password, role, account_status)"
                + " values ('Lower', 'lower-status@campus.test', 'p', 'ROLE_USER', 'active')"))
                .as("values are case-sensitive, matching EnumType.STRING")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static Flyway flyway(DataSource dataSource, String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").cleanDisabled(true)
                .target(target).load();
    }
}
