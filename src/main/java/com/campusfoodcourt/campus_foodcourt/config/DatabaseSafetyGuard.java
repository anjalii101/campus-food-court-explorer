package com.campusfoodcourt.campus_foodcourt.config;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Refuses to let the application use a protected database. The check uses the catalog name
 * reported by the live connection, not the JDBC URL text.
 */
public final class DatabaseSafetyGuard {

    /** Always protected; configuration can add names but can never remove these. */
    public static final Set<String> ALWAYS_PROTECTED = Set.of("campusfoodcourt");

    private DatabaseSafetyGuard() {
    }

    public static void verify(DataSource dataSource, Collection<String> additionalProtectedNames) {
        String current;
        try (Connection connection = dataSource.getConnection()) {
            current = connection.getCatalog();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not determine the target database name", e);
        }
        if (current == null) {
            throw new IllegalStateException("Could not determine the target database name (no catalog reported)");
        }
        if (effectiveProtectedNames(additionalProtectedNames).contains(current.trim().toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("Refusing to connect to protected database '" + current
                    + "'. Point DB_URL at a dedicated development/test database.");
        }
    }

    static Set<String> effectiveProtectedNames(Collection<String> additional) {
        Set<String> names = new LinkedHashSet<>(ALWAYS_PROTECTED);
        if (additional != null) {
            additional.stream()
                    .filter(n -> n != null && !n.isBlank())
                    .map(n -> n.trim().toLowerCase(Locale.ROOT))
                    .forEach(names::add);
        }
        return names;
    }
}
