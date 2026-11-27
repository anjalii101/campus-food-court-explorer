package com.campusfoodcourt.campus_foodcourt.auth;

import com.campusfoodcourt.campus_foodcourt.entity.User;
import com.campusfoodcourt.campus_foodcourt.exception.EmailAlreadyInUseException;
import com.campusfoodcourt.campus_foodcourt.service.AuthService;
import com.campusfoodcourt.campus_foodcourt.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Case-insensitive uniqueness is enforced by the database, and only that violation maps to "email in use". */
class EmailUniquenessTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AuthService authService;

    @Test
    void databaseRejectsEmailsDifferingOnlyInCase() {
        String local = "case-" + UUID.randomUUID();
        insertUser(local + "@Campus.Test");
        assertThatThrownBy(() -> insertUser(local.toUpperCase() + "@campus.test"))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uk_users_email_lower");
    }

    @Test
    void realIndexViolationIsTranslatedToEmailConflict() {
        // A legacy/mixed-case row that the exact-match pre-check cannot see: only the DB index catches it,
        // so this exercises the saveAndFlush -> uk_users_email_lower -> EmailAlreadyInUseException path.
        String local = "index-" + UUID.randomUUID();
        insertUser(local.toUpperCase() + "@CAMPUS.TEST");
        User user = User.builder().name("Dup").email(local + "@campus.test").password("correct-horse-battery").build();

        assertThatThrownBy(() -> authService.register(user)).isInstanceOf(EmailAlreadyInUseException.class);
        assertThat(jdbc.queryForObject("select count(*) from users where lower(email) = ?", Integer.class,
                local + "@campus.test")).isOne();
    }

    @Test
    void unrelatedConstraintFailureIsNotReportedAsEmailConflict() {
        String email = "long-name-" + UUID.randomUUID() + "@campus.test";
        User user = User.builder().name("x".repeat(300)).email(email).password("correct-horse-battery").build();

        // users.name is VARCHAR(255): a different integrity failure must surface as itself, not as a 409.
        assertThatThrownBy(() -> authService.register(user))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(EmailAlreadyInUseException.class);
        assertThat(jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email)).isZero();
    }

    @Test
    void emailConflictDetectionMatchesOnlyTheEmailIndex() {
        String local = "detect-" + UUID.randomUUID();
        insertUser(local + "@campus.test");
        try {
            insertUser(local.toUpperCase() + "@campus.test");
        } catch (DuplicateKeyException e) {
            // JdbcTemplate does not go through Hibernate, so no Hibernate ConstraintViolationException is present
            assertThat(e).hasMessageContaining("uk_users_email_lower");
        }
        assertThat(callIsEmailUniqueViolation(new DataIntegrityViolationException("x",
                new org.hibernate.exception.ConstraintViolationException("dup", null, "uk_users_email_lower"))))
                .isTrue();
        assertThat(callIsEmailUniqueViolation(new DataIntegrityViolationException("x",
                new org.hibernate.exception.ConstraintViolationException("fk", null, "refresh_tokens_user_id_fkey"))))
                .isFalse();
        assertThat(callIsEmailUniqueViolation(new DataIntegrityViolationException("value too long"))).isFalse();
    }

    private void insertUser(String email) {
        jdbc.update("insert into users (name, email, password, role) values ('n', ?, 'p', 'ROLE_USER')", email);
    }

    private static boolean callIsEmailUniqueViolation(Throwable t) {
        try {
            var m = AuthService.class.getDeclaredMethod("isEmailUniqueViolation", Throwable.class);
            m.setAccessible(true);
            return (boolean) m.invoke(null, t);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
