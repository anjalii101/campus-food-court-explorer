package com.campusfoodcourt.campus_foodcourt.config;

import com.campusfoodcourt.campus_foodcourt.CampusFoodcourtApplication;
import com.campusfoodcourt.campus_foodcourt.auth.RefreshToken;
import com.campusfoodcourt.campus_foodcourt.entity.Dish;
import com.campusfoodcourt.campus_foodcourt.entity.Outlet;
import com.campusfoodcourt.campus_foodcourt.entity.Review;
import com.campusfoodcourt.campus_foodcourt.entity.User;
import com.campusfoodcourt.campus_foodcourt.support.TestDatabase;
import jakarta.persistence.Entity;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.SessionFactory;
import org.hibernate.tool.schema.spi.SchemaManagementException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import javax.sql.DataSource;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Applies the Flyway migrations to a brand-new, empty embedded PostgreSQL database and validates the JPA
 * entity mappings against the resulting schema with Hibernate's schema validator, using the same naming
 * strategies Spring Boot uses at runtime.
 */
class SchemaMappingCompatibilityTest {

    /** Entities whose tables are created by the current Flyway migrations. */
    private static final List<Class<?>> MIGRATED_ENTITIES = List.of(User.class, RefreshToken.class);

    /**
     * Legacy entities deliberately NOT migrated in Milestone 1 and NOT validated here; their tables arrive in
     * their own milestone (money as BIGINT paise). Only listed so a newly added entity cannot silently escape
     * this test - no table is expected for them.
     */
    private static final List<Class<?>> PENDING_MIGRATION = List.of(Outlet.class, Dish.class, Review.class);

    private static DataSource cleanDatabase;
    private static MigrateResult firstMigration;

    @BeforeAll
    static void migrateCleanDatabase() {
        cleanDatabase = TestDatabase.createEmptyDatabase("cfc_schema_check");
        firstMigration = flyway(cleanDatabase).migrate();
    }

    @Test
    void migrationsApplyCleanlyToAnEmptyDatabase() {
        assertThat(firstMigration.success).isTrue();
        assertThat(firstMigration.migrations).extracting(m -> m.version).containsExactly("1", "2", "3");
    }

    @Test
    void migrationsAreRepeatableAndValidate() {
        assertThat(flyway(cleanDatabase).migrate().migrationsExecuted).isZero();
        assertThatCode(() -> flyway(cleanDatabase).validate()).doesNotThrowAnyException();
    }

    @Test
    void migratedEntityMappingsMatchTheFlywaySchema() {
        assertThatCode(() -> validate(cleanDatabase, MIGRATED_ENTITIES)).doesNotThrowAnyException();
    }

    @Test
    void validationReallyRunsNegativeControl() {
        // Same two entities against an empty, unmigrated database: validation must fail on a missing table.
        DataSource unmigrated = TestDatabase.createEmptyDatabase("cfc_schema_unmigrated");
        assertThatThrownBy(() -> validate(unmigrated, MIGRATED_ENTITIES))
                .isInstanceOf(SchemaManagementException.class)
                .hasMessageContaining("missing table");
    }

    @Test
    void everyEntityIsEitherValidatedOrExplicitlyPendingMigration() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        Set<String> discovered = scanner.findCandidateComponents(CampusFoodcourtApplication.class.getPackageName())
                .stream().map(b -> b.getBeanClassName()).collect(Collectors.toSet());

        Set<String> accountedFor = java.util.stream.Stream.concat(MIGRATED_ENTITIES.stream(), PENDING_MIGRATION.stream())
                .map(Class::getName).collect(Collectors.toSet());
        assertThat(discovered).isEqualTo(accountedFor);
    }

    private static Flyway flyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .load();
    }

    /** Equivalent to running the app with spring.jpa.hibernate.ddl-auto=validate for the given entities. */
    private static void validate(DataSource dataSource, List<Class<?>> entities) {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.JAKARTA_NON_JTA_DATASOURCE, dataSource)
                .applySetting(AvailableSettings.HBM2DDL_AUTO, "validate")
                .build();
        try {
            MetadataSources sources = new MetadataSources(registry);
            entities.forEach(sources::addAnnotatedClass);
            Metadata metadata = sources.getMetadataBuilder()
                    .applyPhysicalNamingStrategy(new CamelCaseToUnderscoresNamingStrategy())
                    .applyImplicitNamingStrategy(new SpringImplicitNamingStrategy())
                    .build();
            try (SessionFactory ignored = metadata.buildSessionFactory()) {
                // building the session factory performs the schema validation
            }
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }
}
