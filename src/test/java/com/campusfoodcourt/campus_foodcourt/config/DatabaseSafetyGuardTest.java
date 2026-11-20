package com.campusfoodcourt.campus_foodcourt.config;

import com.campusfoodcourt.campus_foodcourt.support.TestDatabase;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseSafetyGuardTest {

    private final DataSource testDb = TestDatabase.dataSource(TestDatabase.DATABASE_NAME);

    @Test
    void rejectsConfiguredProtectedDatabaseByLiveCatalogName() {
        assertThatThrownBy(() -> DatabaseSafetyGuard.verify(testDb, List.of(" CFC_TEST ")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to connect to protected database 'cfc_test'");
    }

    @Test
    void allowsUnprotectedDatabase() {
        assertThatCode(() -> DatabaseSafetyGuard.verify(testDb, List.of("something_else")))
                .doesNotThrowAnyException();
        assertThatCode(() -> DatabaseSafetyGuard.verify(testDb, null)).doesNotThrowAnyException();
    }

    @Test
    void legacyNameIsAlwaysProtectedEvenWithEmptyOrDifferentConfiguration() {
        TestDatabase.legacyNamedDatabaseJdbcUrl(); // ensure the embedded look-alike exists
        DataSource legacyNamed = TestDatabase.dataSource("campusfoodcourt");

        for (List<String> configured : List.of(List.<String>of(), List.of("other_db"), List.of(""))) {
            assertThatThrownBy(() -> DatabaseSafetyGuard.verify(legacyNamed, configured))
                    .as("configured=%s", configured)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("'campusfoodcourt'");
        }
        assertThat(DatabaseSafetyGuard.effectiveProtectedNames(List.of("x"))).contains("campusfoodcourt", "x");
    }
}
