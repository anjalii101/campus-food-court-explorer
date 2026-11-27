package com.campusfoodcourt.campus_foodcourt.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * One throwaway PostgreSQL server per test JVM, started from embedded binaries on a random
 * port in a temporary directory. It is unrelated to any locally installed PostgreSQL server.
 */
public final class TestDatabase {

    public static final String DATABASE_NAME = "cfc_test";
    public static final String USERNAME = "postgres";
    public static final String PASSWORD = "postgres";

    private static final EmbeddedPostgres SERVER = start();

    private TestDatabase() {
    }

    private static EmbeddedPostgres start() {
        try {
            EmbeddedPostgres server = EmbeddedPostgres.builder().start();
            try (Connection c = server.getPostgresDatabase().getConnection(); Statement s = c.createStatement()) {
                s.execute("CREATE DATABASE " + DATABASE_NAME);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    server.close();
                } catch (IOException ignored) {
                    // best effort on JVM exit
                }
            }));
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start embedded PostgreSQL", e);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create test database", e);
        }
    }

    public static String jdbcUrl() {
        return SERVER.getJdbcUrl(USERNAME, DATABASE_NAME);
    }

    public static DataSource dataSource(String databaseName) {
        return SERVER.getDatabase(USERNAME, databaseName);
    }

    private static String legacyNamedDatabaseUrl;

    /**
     * A throwaway database on the EMBEDDED server that happens to be named "campusfoodcourt", used to prove
     * the application refuses that name. It is unrelated to the real legacy database on the local install.
     */
    public static synchronized String legacyNamedDatabaseJdbcUrl() {
        if (legacyNamedDatabaseUrl == null) {
            createEmptyDatabase("campusfoodcourt");
            legacyNamedDatabaseUrl = SERVER.getJdbcUrl(USERNAME, "campusfoodcourt");
        }
        return legacyNamedDatabaseUrl;
    }

    /** Creates an additional empty database on the embedded server (never on a local PostgreSQL install). */
    public static DataSource createEmptyDatabase(String databaseName) {
        if (!databaseName.matches("[a-z_][a-z0-9_]*")) {
            throw new IllegalArgumentException("Unsafe database name: " + databaseName);
        }
        try (Connection c = SERVER.getPostgresDatabase().getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + databaseName);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create database " + databaseName, e);
        }
        return dataSource(databaseName);
    }
}
