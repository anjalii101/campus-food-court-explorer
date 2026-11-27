package com.campusfoodcourt.campus_foodcourt.config;

import com.campusfoodcourt.campus_foodcourt.CampusFoodcourtApplication;
import com.campusfoodcourt.campus_foodcourt.auth.RefreshToken;
import com.campusfoodcourt.campus_foodcourt.entity.User;
import com.campusfoodcourt.campus_foodcourt.menu.Dish;
import com.campusfoodcourt.campus_foodcourt.outlets.Outlet;
import com.campusfoodcourt.campus_foodcourt.reviews.Review;
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
import org.hibernate.mapping.Column;
import org.hibernate.mapping.Table;
import org.hibernate.SessionFactory;
import org.hibernate.tool.schema.spi.SchemaManagementException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
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

    /** Entities whose tables are created by the current Flyway migrations: every entity in the application. */
    private static final List<Class<?>> MIGRATED_ENTITIES = List.of(
            User.class, RefreshToken.class, Outlet.class, Dish.class, Review.class);

    /** Catalogue entities plus User, which the catalogue tables reference by foreign key. */
    private static final List<Class<?>> CATALOGUE_ENTITIES = List.of(User.class, Outlet.class, Dish.class, Review.class);

    /** Exact columns of each catalogue table (V4-V6). Hibernate's validator alone would not notice unmapped columns. */
    private static final Map<String, Set<String>> CATALOGUE_COLUMNS = Map.of(
            "outlets", Set.of("id", "vendor_user_id", "name", "description", "location", "contact_phone",
                    "contact_email", "status", "manually_closed", "version", "created_at", "updated_at"),
            "outlet_operating_hours", Set.of("outlet_id", "day_of_week", "opens_at", "closes_at"),
            "dishes", Set.of("id", "outlet_id", "name", "description", "price_paise", "dietary_type", "available",
                    "stock_quantity", "image_ref", "deleted_at", "version", "created_at", "updated_at"),
            "reviews", Set.of("id", "user_id", "dish_id", "rating", "review_text", "created_at", "updated_at"));

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
        assertThat(firstMigration.migrations).extracting(m -> m.version)
                .containsExactly("1", "2", "3", "4", "5", "6");
        assertThat(firstMigration.migrations).extracting(m -> m.filepath)
                .anyMatch(p -> p.endsWith("V4__create_outlets.sql"))
                .anyMatch(p -> p.endsWith("V5__create_dishes.sql"))
                .anyMatch(p -> p.endsWith("V6__create_reviews.sql"));
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
        // Same entities against an empty, unmigrated database: validation must fail on a missing table.
        DataSource unmigrated = TestDatabase.createEmptyDatabase("cfc_schema_unmigrated");
        assertThatThrownBy(() -> validate(unmigrated, MIGRATED_ENTITIES))
                .isInstanceOf(SchemaManagementException.class)
                .hasMessageContaining("missing table");
    }

    @Test
    void catalogueValidationNeedsTheCatalogueMigrationsNegativeControl() {
        // users exists after V3, the catalogue tables do not: validation must fail on a catalogue table, which proves
        // the catalogue entities themselves are validated rather than passing because of an earlier failure.
        DataSource beforeCatalogue = TestDatabase.createEmptyDatabase("cfc_schema_before_catalogue");
        Flyway.configure().dataSource(beforeCatalogue).locations("classpath:db/migration").cleanDisabled(true)
                .target("3").load().migrate();

        assertThatThrownBy(() -> validate(beforeCatalogue, CATALOGUE_ENTITIES))
                .isInstanceOf(SchemaManagementException.class)
                .hasMessageContaining("missing table")
                .hasMessageMatching("(?s).*\\b(outlets|outlet_operating_hours|dishes|reviews)\\b.*");
    }

    @Test
    void catalogueEntitiesMapExactlyTheColumnsOfTheirTables() {
        withMetadata(cleanDatabase, CATALOGUE_ENTITIES, metadata -> {
            Map<String, Table> mappedTables = Map.of(
                    "outlets", metadata.getEntityBinding(Outlet.class.getName()).getTable(),
                    "outlet_operating_hours",
                    metadata.getCollectionBinding(Outlet.class.getName() + ".operatingHours").getCollectionTable(),
                    "dishes", metadata.getEntityBinding(Dish.class.getName()).getTable(),
                    "reviews", metadata.getEntityBinding(Review.class.getName()).getTable());

            JdbcTemplate jdbc = new JdbcTemplate(cleanDatabase);
            CATALOGUE_COLUMNS.forEach((table, expectedColumns) -> {
                Table mapped = mappedTables.get(table);
                assertThat(mapped.getName()).as("table mapped for %s", table).isEqualTo(table);
                Set<String> mappedColumns = mapped.getColumns().stream().map(Column::getName)
                        .collect(Collectors.toSet());
                Set<String> databaseColumns = new HashSet<>(jdbc.queryForList("select column_name from"
                        + " information_schema.columns where table_schema = 'public' and table_name = ?",
                        String.class, table));

                assertThat(databaseColumns).as("columns created by the migration for %s", table)
                        .isEqualTo(expectedColumns);
                assertThat(mappedColumns).as("columns mapped by JPA for %s", table).isEqualTo(expectedColumns);
            });
        });
    }

    @Test
    void everyEntityIsValidatedAgainstTheMigratedSchema() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        Set<String> discovered = scanner.findCandidateComponents(CampusFoodcourtApplication.class.getPackageName())
                .stream().map(b -> b.getBeanClassName()).collect(Collectors.toSet());

        Set<String> validated = MIGRATED_ENTITIES.stream().map(Class::getName).collect(Collectors.toSet());
        assertThat(discovered).isEqualTo(validated);
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
            try (SessionFactory ignored = buildMetadata(registry, entities).buildSessionFactory()) {
                // building the session factory performs the schema validation
            }
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    /** Hibernate's mapping model of the given entities, built with Spring Boot's naming strategies (no validation). */
    private static void withMetadata(DataSource dataSource, List<Class<?>> entities, Consumer<Metadata> inspection) {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.JAKARTA_NON_JTA_DATASOURCE, dataSource)
                .applySetting(AvailableSettings.HBM2DDL_AUTO, "none")
                .build();
        try {
            inspection.accept(buildMetadata(registry, entities));
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    private static Metadata buildMetadata(StandardServiceRegistry registry, List<Class<?>> entities) {
        MetadataSources sources = new MetadataSources(registry);
        entities.forEach(sources::addAnnotatedClass);
        return sources.getMetadataBuilder()
                .applyPhysicalNamingStrategy(new CamelCaseToUnderscoresNamingStrategy())
                .applyImplicitNamingStrategy(new SpringImplicitNamingStrategy())
                .build();
    }
}
