package com.campusfoodcourt.campus_foodcourt.config;

import com.campusfoodcourt.campus_foodcourt.CampusFoodcourtApplication;
import com.campusfoodcourt.campus_foodcourt.support.TestDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boots the real application with unsafe settings and checks that startup is refused. All databases
 * involved live on the embedded test server; "campusfoodcourt" here is an embedded look-alike.
 */
class StartupSafetyTest {

    // ------------------------------------------------------------- protected database

    @Test
    void refusesConfiguredProtectedDatabase() {
        assertStartupFails("Refusing to connect to protected database 'cfc_test'", new String[]{"test"},
                datasource(TestDatabase.jdbcUrl()), "--app.database.protected-names=cfc_test");
    }

    @Test
    void refusesLegacyDatabaseNameEvenWithFlywayDisabled() {
        assertStartupFails("Refusing to connect to protected database 'campusfoodcourt'", new String[]{"test"},
                datasource(TestDatabase.legacyNamedDatabaseJdbcUrl()), "--spring.flyway.enabled=false");
    }

    @Test
    void legacyDatabaseProtectionCannotBeRemovedByConfiguration() {
        assertStartupFails("Refusing to connect to protected database 'campusfoodcourt'", new String[]{"test"},
                datasource(TestDatabase.legacyNamedDatabaseJdbcUrl()),
                "--app.database.protected-names=", "--DB_PROTECTED_NAMES=", "--spring.flyway.enabled=false");
    }

    // ------------------------------------------------------------- secrets

    @Test
    void refusesToStartWithoutJwtSecret() {
        assertStartupFails("app.jwt.secret", new String[]{"test"}, datasource(TestDatabase.jdbcUrl()),
                "--app.jwt.secret=");
    }

    @Test
    void refusesToStartWithWeakJwtSecret() {
        assertStartupFails("at least 32 bytes", new String[]{"test"}, datasource(TestDatabase.jdbcUrl()),
                "--app.jwt.secret=c2hvcnQtc2VjcmV0");
    }

    // ------------------------------------------------------------- production profile

    @Test
    void prodProfileRefusesInsecureRefreshCookie() {
        assertStartupFails("app.auth.cookie.secure must be true in the prod profile", new String[]{"test", "prod"},
                datasource(TestDatabase.jdbcUrl()), "--app.auth.cookie.secure=false");
    }

    @Test
    void dotEnvFileIsImportedOutsideProdButNotInProd(@TempDir Path dir) throws Exception {
        Path envFile = dir.resolve("test.env");
        Files.writeString(envFile, "app.test.dotenv-marker=loaded\n");
        String envArg = "--APP_ENV_FILE=" + envFile.toAbsolutePath().toString().replace('\\', '/');

        try (ConfigurableApplicationContext ctx = start(new String[]{"test"}, datasource(TestDatabase.jdbcUrl()),
                envArg)) {
            assertThat(ctx.getEnvironment().getProperty("app.test.dotenv-marker")).isEqualTo("loaded");
        }
        try (ConfigurableApplicationContext ctx = start(new String[]{"test", "prod"},
                datasource(TestDatabase.jdbcUrl()), envArg)) {
            assertThat(ctx.getEnvironment().getProperty("app.test.dotenv-marker")).isNull();
            assertThat(ctx.getEnvironment().getProperty("app.auth.cookie.secure")).isEqualTo("true");
        }
    }

    // ------------------------------------------------------------- helpers

    private static List<String> datasource(String jdbcUrl) {
        return List.of(
                "--spring.datasource.url=" + jdbcUrl,
                "--spring.datasource.username=" + TestDatabase.USERNAME,
                "--spring.datasource.password=" + TestDatabase.PASSWORD,
                "--server.port=0");
    }

    private static ConfigurableApplicationContext start(String[] profiles, List<String> base, String... overrides) {
        List<String> args = new ArrayList<>(base);
        args.addAll(List.of(overrides));
        return new SpringApplicationBuilder(CampusFoodcourtApplication.class)
                .profiles(profiles)
                .run(args.toArray(String[]::new));
    }

    private static void assertStartupFails(String expectedMessagePart, String[] profiles, List<String> base,
                                           String... overrides) {
        assertThatThrownBy(() -> {
            try (ConfigurableApplicationContext ignored = start(profiles, base, overrides)) {
                // should not get here
            }
        }).satisfies(e -> {
            StringBuilder messages = new StringBuilder();
            for (Throwable t = e; t != null; t = t.getCause()) {
                messages.append(t.getMessage()).append('\n');
            }
            assertThat(messages.toString()).contains(expectedMessagePart);
        });
    }
}
