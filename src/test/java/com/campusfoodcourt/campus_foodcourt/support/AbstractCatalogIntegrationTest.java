package com.campusfoodcourt.campus_foodcourt.support;

import jakarta.persistence.EntityManager;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Shared fixtures for the outlet / menu / review tests. All data lives in the embedded throwaway test database and
 * every fixture uses unique names, so tests stay independent although they share one database and commit.
 */
public abstract class AbstractCatalogIntegrationTest extends AbstractIntegrationTest {

    /** PostgreSQL SQLSTATE codes. */
    protected static final String UNIQUE_VIOLATION = "23505";
    protected static final String CHECK_VIOLATION = "23514";
    protected static final String FOREIGN_KEY_VIOLATION = "23503";
    protected static final String NOT_NULL_VIOLATION = "23502";
    protected static final String STRING_TOO_LONG = "22001";

    /** A Monday, 10:00 in Asia/Kolkata. */
    protected static final Instant T0 = Instant.parse("2026-01-05T04:30:00Z");

    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected TransactionTemplate tx;
    @Autowired
    protected EntityManager entityManager;

    protected static String unique(String prefix) {
        return prefix + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    protected long insertUser() {
        return jdbc.queryForObject(
                "insert into users (name, email, password, role) values (?, ?, ?, 'ROLE_USER') returning id",
                Long.class, "Catalog Test", "catalog-" + UUID.randomUUID() + "@campus.test", "not-a-real-hash");
    }

    protected UUID insertOutlet(long vendorUserId, String name) {
        return jdbc.queryForObject("insert into outlets (id, vendor_user_id, name, location, status, created_at,"
                        + " updated_at) values (gen_random_uuid(), ?, ?, 'North Block', 'ACTIVE', now(), now())"
                        + " returning id",
                UUID.class, vendorUserId, name);
    }

    protected UUID insertOutlet() {
        return insertOutlet(insertUser(), unique("Outlet"));
    }

    protected UUID insertDish(UUID outletId, String name) {
        return jdbc.queryForObject("insert into dishes (id, outlet_id, name, price_paise, dietary_type, created_at,"
                        + " updated_at) values (gen_random_uuid(), ?, ?, 5000, 'VEG', now(), now()) returning id",
                UUID.class, outletId, name);
    }

    protected UUID insertReview(long userId, UUID dishId, int rating) {
        return jdbc.queryForObject("insert into reviews (id, user_id, dish_id, rating, created_at, updated_at)"
                        + " values (gen_random_uuid(), ?, ?, ?, now(), now()) returning id",
                UUID.class, userId, dishId, rating);
    }

    /** Asserts the statement fails in PostgreSQL with the given SQLSTATE, naming the given constraint. */
    protected static void assertViolates(String sqlState, String constraintName, ThrowingCallable statement) {
        assertThatThrownBy(statement).satisfies(e -> {
            SQLException sql = sqlException(e);
            assertThat(sql.getSQLState()).as("SQLSTATE of: %s", sql.getMessage()).isEqualTo(sqlState);
            if (constraintName != null) {
                assertThat(sql.getMessage()).contains("\"" + constraintName + "\"");
            }
        });
    }

    private static SQLException sqlException(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql;
            }
        }
        throw new AssertionError("Expected a database error but got: " + e, e);
    }
}
