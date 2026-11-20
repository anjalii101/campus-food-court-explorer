package com.campusfoodcourt.campus_foodcourt.config;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FlywayConfig {

    /**
     * Second line of defence: Flyway can be pointed at its own database (spring.flyway.url), which is not
     * an application DataSource bean, so every migration run checks its own target as well.
     */
    @Bean
    public FlywayMigrationStrategy guardedMigrationStrategy(DatabaseProperties databaseProperties) {
        return flyway -> {
            DatabaseSafetyGuard.verify(flyway.getConfiguration().getDataSource(), databaseProperties.protectedNames());
            flyway.migrate();
        };
    }
}
