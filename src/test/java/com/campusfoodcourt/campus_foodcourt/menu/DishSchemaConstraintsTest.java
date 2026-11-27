package com.campusfoodcourt.campus_foodcourt.menu;

import com.campusfoodcourt.campus_foodcourt.support.AbstractCatalogIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Database-level rules of V5 (dishes), exercised with plain SQL - no JPA checks involved. */
class DishSchemaConstraintsTest extends AbstractCatalogIntegrationTest {

    private int insertDish(UUID outlet, String name, long pricePaise, String dietaryType, Integer stock) {
        return jdbc.update("insert into dishes (id, outlet_id, name, price_paise, dietary_type, stock_quantity,"
                + " created_at, updated_at) values (gen_random_uuid(), ?, ?, ?, ?, ?, now(), now())",
                outlet, name, pricePaise, dietaryType, stock);
    }

    @Test
    void priceMustBeGreaterThanZero() {
        UUID outlet = insertOutlet();
        assertViolates(CHECK_VIOLATION, "ck_dishes_price_positive", () -> insertDish(outlet, unique("Free"), 0, "VEG", null));
        assertViolates(CHECK_VIOLATION, "ck_dishes_price_positive", () -> insertDish(outlet, unique("Negative"), -100, "VEG", null));
        assertThat(insertDish(outlet, unique("One paisa"), 1, "VEG", null)).isOne();
    }

    @Test
    void stockMayBeNullOrZeroButNotNegative() {
        UUID outlet = insertOutlet();
        assertThat(insertDish(outlet, unique("Untracked"), 1000, "VEG", null)).isOne();
        assertThat(insertDish(outlet, unique("Sold out"), 1000, "VEG", 0)).isOne();
        assertViolates(CHECK_VIOLATION, "ck_dishes_stock_not_negative",
                () -> insertDish(outlet, unique("Oversold"), 1000, "VEG", -1));
    }

    @Test
    void dietaryTypeIsRestrictedToVegNonVegAndEgg() {
        UUID outlet = insertOutlet();
        for (String type : new String[] {"VEG", "NON_VEG", "EGG"}) {
            assertThat(insertDish(outlet, unique(type), 1000, type, null)).isOne();
        }
        assertViolates(CHECK_VIOLATION, "ck_dishes_dietary_type", () -> insertDish(outlet, unique("Vegan"), 1000, "VEGAN", null));
        assertViolates(CHECK_VIOLATION, "ck_dishes_dietary_type", () -> insertDish(outlet, unique("Lower"), 1000, "veg", null));
        assertViolates(NOT_NULL_VIOLATION, null, () -> insertDish(outlet, unique("None"), 1000, null, null));
    }

    @Test
    void availableDefaultsToTrueAndStockToNull() {
        UUID dish = insertDish(insertOutlet(), unique("Defaults"));
        assertThat(jdbc.queryForMap("select available, stock_quantity from dishes where id = ?", dish))
                .containsEntry("available", true).containsEntry("stock_quantity", null);
    }

    @Test
    void dishNamesAreUniqueIgnoringCaseWithinOneOutlet() {
        UUID outlet = insertOutlet();
        String name = unique("Masala Dosa");
        insertDish(outlet, name);

        assertViolates(UNIQUE_VIOLATION, "uk_dishes_outlet_name_lower_active", () -> insertDish(outlet, name.toUpperCase()));
        // The same name at a different outlet is allowed.
        assertThat(insertDish(insertOutlet(), name)).isNotNull();
    }

    @Test
    void aSoftDeletedDishNameCanBeReused() {
        UUID outlet = insertOutlet();
        String name = unique("Paneer Roll");
        UUID original = insertDish(outlet, name);
        jdbc.update("update dishes set deleted_at = now() where id = ?", original);

        UUID replacement = insertDish(outlet, name.toLowerCase());
        assertThat(replacement).isNotEqualTo(original);

        // Several deleted generations of the same name may exist, but only one live dish.
        jdbc.update("update dishes set deleted_at = now() where id = ?", replacement);
        insertDish(outlet, name);
        assertViolates(UNIQUE_VIOLATION, "uk_dishes_outlet_name_lower_active", () -> insertDish(outlet, name));
        assertThat(jdbc.queryForObject("select count(*) from dishes where outlet_id = ? and lower(name) = lower(?)",
                Long.class, outlet, name)).isEqualTo(3);
    }

    @Test
    void restoringADeletedDishIsBlockedWhileANewDishUsesItsName() {
        UUID outlet = insertOutlet();
        String name = unique("Idli");
        UUID original = insertDish(outlet, name);
        jdbc.update("update dishes set deleted_at = now() where id = ?", original);
        insertDish(outlet, name);

        assertViolates(UNIQUE_VIOLATION, "uk_dishes_outlet_name_lower_active",
                () -> jdbc.update("update dishes set deleted_at = null where id = ?", original));
    }

    @Test
    void dishMustBelongToAnExistingOutlet() {
        assertViolates(FOREIGN_KEY_VIOLATION, "fk_dishes_outlet", () -> insertDish(UUID.randomUUID(), unique("Orphan")));
    }

    @Test
    void deletingAnOutletThatHasDishesIsRestrictedEvenIfAllAreSoftDeleted() {
        UUID outlet = insertOutlet();
        jdbc.update("insert into outlet_operating_hours (outlet_id, day_of_week, opens_at, closes_at)"
                + " values (?, 1, '09:00', '17:00')", outlet);
        UUID dish = insertDish(outlet, unique("Vada Pav"));
        jdbc.update("update dishes set deleted_at = now() where id = ?", dish);

        assertViolates(FOREIGN_KEY_VIOLATION, "fk_dishes_outlet",
                () -> jdbc.update("delete from outlets where id = ?", outlet));
        // The failed delete removed nothing - not even the operating hours that would otherwise cascade.
        assertThat(jdbc.queryForObject("select count(*) from outlet_operating_hours where outlet_id = ?", Long.class,
                outlet)).isOne();
    }

    @Test
    void nameAndImageRefLengthsAreEnforced() {
        UUID outlet = insertOutlet();
        assertViolates(STRING_TOO_LONG, null, () -> insertDish(outlet, "x".repeat(121)));
        UUID dish = insertDish(outlet, unique("Image"));
        assertViolates(STRING_TOO_LONG, null,
                () -> jdbc.update("update dishes set image_ref = ? where id = ?", "k".repeat(501), dish));
    }

    @Test
    void updatedAtCannotBeBeforeCreatedAt() {
        UUID dish = insertDish(insertOutlet(), unique("Clocked"));
        assertViolates(CHECK_VIOLATION, "ck_dishes_updated_after_created", () -> jdbc.update(
                "update dishes set updated_at = created_at - interval '1 microsecond' where id = ?", dish));
    }
}
