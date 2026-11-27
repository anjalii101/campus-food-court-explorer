package com.campusfoodcourt.campus_foodcourt.config;

import com.campusfoodcourt.campus_foodcourt.menu.Dish;
import com.campusfoodcourt.campus_foodcourt.menu.DishRepository;
import com.campusfoodcourt.campus_foodcourt.outlets.Outlet;
import com.campusfoodcourt.campus_foodcourt.outlets.OutletRepository;
import com.campusfoodcourt.campus_foodcourt.reviews.Review;
import com.campusfoodcourt.campus_foodcourt.reviews.ReviewRepository;
import com.campusfoodcourt.campus_foodcourt.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.MappingMetamodel;
import org.hibernate.persister.entity.Joinable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards against two JPA mappings for one table (as happened while the legacy catalogue classes still existed).
 * Reads the table of every entity and collection from the running application's Hibernate mapping model, so any
 * extra entity mapped onto a catalogue table - whatever its class or entity name - fails this test.
 */
class CatalogueTableMappingTest extends AbstractIntegrationTest {

    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private ApplicationContext context;

    private MappingMetamodel mappingMetamodel() {
        return entityManagerFactory.unwrap(SessionFactoryImplementor.class).getMappingMetamodel();
    }

    /** Physical table name -> classes of all entities whose rows live in that table. */
    private Map<String, List<String>> entitiesByTable() {
        Map<String, List<String>> byTable = new TreeMap<>();
        mappingMetamodel().forEachEntityDescriptor(entity -> byTable
                .computeIfAbsent(tableName((Joinable) entity), t -> new ArrayList<>())
                .add(entity.getMappedClass().getName()));
        return byTable;
    }

    /** Physical table name -> roles of all collections stored in their own table. */
    private Map<String, List<String>> collectionsByTable() {
        Map<String, List<String>> byTable = new TreeMap<>();
        mappingMetamodel().forEachCollectionDescriptor(collection -> byTable
                .computeIfAbsent(tableName((Joinable) collection), t -> new ArrayList<>())
                .add(collection.getRole()));
        return byTable;
    }

    private static String tableName(Joinable joinable) {
        String qualified = joinable.getTableName().replace("\"", "");
        return qualified.substring(qualified.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    @Test
    void eachCatalogueTableIsMappedByExactlyTheNewEntity() {
        Map<String, List<String>> byTable = entitiesByTable();

        assertThat(byTable.get("outlets")).containsExactly(Outlet.class.getName());
        assertThat(byTable.get("dishes")).containsExactly(Dish.class.getName());
        assertThat(byTable.get("reviews")).containsExactly(Review.class.getName());
    }

    @Test
    void noTableIsMappedByMoreThanOneEntity() {
        assertThat(entitiesByTable()).allSatisfy((table, entities) ->
                assertThat(entities).as("entities mapped to table %s", table).hasSize(1));
    }

    @Test
    void operatingHoursTableBelongsOnlyToTheOutletHoursCollection() {
        assertThat(collectionsByTable().get("outlet_operating_hours"))
                .containsExactly(Outlet.class.getName() + ".operatingHours");
        assertThat(entitiesByTable()).doesNotContainKey("outlet_operating_hours");
    }

    @Test
    void catalogueEntitiesUseTheirDefaultJpaNames() {
        var metamodel = entityManagerFactory.getMetamodel();
        assertThat(metamodel.entity(Outlet.class).getName()).isEqualTo("Outlet");
        assertThat(metamodel.entity(Dish.class).getName()).isEqualTo("Dish");
        assertThat(metamodel.entity(Review.class).getName()).isEqualTo("Review");
    }

    @Test
    void catalogueRepositoriesUseTheirDefaultBeanNames() {
        assertThat(context.getBeanNamesForType(OutletRepository.class)).containsExactly("outletRepository");
        assertThat(context.getBeanNamesForType(DishRepository.class)).containsExactly("dishRepository");
        assertThat(context.getBeanNamesForType(ReviewRepository.class)).containsExactly("reviewRepository");
    }
}
