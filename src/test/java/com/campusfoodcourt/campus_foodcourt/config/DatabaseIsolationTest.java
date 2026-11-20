package com.campusfoodcourt.campus_foodcourt.config;

import com.campusfoodcourt.campus_foodcourt.support.AbstractIntegrationTest;
import com.campusfoodcourt.campus_foodcourt.support.TestDatabase;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves tests and default configuration can never reach the legacy "campusfoodcourt" database. */
class DatabaseIsolationTest extends AbstractIntegrationTest {

    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private Flyway flyway;
    @Autowired
    private DatabaseProperties databaseProperties;

    @Test
    void applicationDataSourceIsTheEmbeddedTestDatabase() {
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("cfc_test");
        String url = ((HikariDataSource) dataSource).getJdbcUrl();
        assertThat(url).isEqualTo(TestDatabase.jdbcUrl()).doesNotContain(":5432/");
    }

    @Test
    void flywayMigratesTheSameTestDatabase() throws Exception {
        try (Connection c = flyway.getConfiguration().getDataSource().getConnection()) {
            assertThat(c.getCatalog()).isEqualTo("cfc_test");
        }
    }

    @Test
    void legacyDatabaseIsAlwaysProtectedInCode() {
        assertThat(DatabaseSafetyGuard.ALWAYS_PROTECTED).containsExactly("campusfoodcourt");
        assertThat(DatabaseSafetyGuard.effectiveProtectedNames(databaseProperties.protectedNames()))
                .contains("campusfoodcourt");
    }

    @Test
    void defaultConfigurationHasNoImplicitDatabaseAndLocalProfileUsesDevDatabase() throws Exception {
        Properties base = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));
        assertThat(base.getProperty("spring.datasource.url")).isEqualTo("${DB_URL}");
        assertThat(base.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("none");
        assertThat(base.getProperty("spring.flyway.clean-disabled")).isEqualTo("true");

        Properties local = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application-local.properties"));
        assertThat(local.getProperty("spring.datasource.url"))
                .endsWith("/campusfoodcourt_dev}")
                .doesNotContain("/campusfoodcourt}");
    }
}
