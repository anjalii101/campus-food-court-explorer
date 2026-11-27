package com.campusfoodcourt.campus_foodcourt.menu;

import com.campusfoodcourt.campus_foodcourt.outlets.Outlet;
import com.campusfoodcourt.campus_foodcourt.outlets.OutletRepository;
import com.campusfoodcourt.campus_foodcourt.support.AbstractCatalogIntegrationTest;
import com.campusfoodcourt.campus_foodcourt.support.MutableClock;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** JPA mapping of {@link Dish} against the V5 schema. */
class DishPersistenceTest extends AbstractCatalogIntegrationTest {

    @Autowired
    private DishRepository dishes;
    @Autowired
    private OutletRepository outlets;

    private final MutableClock clock = new MutableClock(T0);

    private Outlet savedOutlet() {
        return outlets.saveAndFlush(new Outlet(insertUser(), unique("Outlet"), "Food Street", clock));
    }

    private Dish newDish(Outlet outlet, long pricePaise) {
        return new Dish(outlet, unique("Dish"), pricePaise, DietaryType.VEG, clock);
    }

    @Test
    void idIsAGeneratedUuidAndOutletForeignKeyIsWritten() {
        Outlet outlet = savedOutlet();
        Dish dish = newDish(outlet, 4500);
        assertThat(dish.getId()).isNull();

        dishes.saveAndFlush(dish);

        assertThat(dish.getId()).isNotNull();
        assertThat(dish.getId().version()).isEqualTo(4);
        assertThat(jdbc.queryForObject("select outlet_id from dishes where id = ?", UUID.class, dish.getId()))
                .isEqualTo(outlet.getId());
        assertThat(dish.getVersion()).isZero();
    }

    @Test
    void pricesInPaiseRoundTripExactly() {
        Outlet outlet = savedOutlet();
        // 1 paisa, an ordinary price (Rs 123.45), a value a double cannot represent exactly, and the BIGINT maximum.
        long[] prices = {1L, 12_345L, 9_007_199_254_740_993L, Long.MAX_VALUE};
        for (long price : prices) {
            UUID id = dishes.saveAndFlush(newDish(outlet, price)).getId();
            assertThat(jdbc.queryForObject("select price_paise from dishes where id = ?", Long.class, id)).isEqualTo(price);
            assertThat(dishes.findById(id).orElseThrow().getPricePaise()).isEqualTo(price);
        }
    }

    @Test
    void nullStockMeansUntrackedAndIsPreservedSeparatelyFromZero() {
        Outlet outlet = savedOutlet();
        Dish untracked = dishes.saveAndFlush(newDish(outlet, 1000));
        Dish soldOut = newDish(outlet, 1000);
        soldOut.trackStock(0, clock);
        dishes.saveAndFlush(soldOut);

        assertThat(dishes.findById(untracked.getId()).orElseThrow().getStockQuantity()).isNull();
        assertThat(dishes.findById(soldOut.getId()).orElseThrow().getStockQuantity()).isZero();
        assertThat(jdbc.queryForObject("select stock_quantity is null from dishes where id = ?", Boolean.class,
                untracked.getId())).isTrue();

        Dish reloaded = dishes.findById(soldOut.getId()).orElseThrow();
        reloaded.stopTrackingStock(clock);
        dishes.saveAndFlush(reloaded);
        assertThat(dishes.findById(soldOut.getId()).orElseThrow().getStockQuantity()).isNull();
    }

    @Test
    void allFieldsRoundTrip() {
        Dish dish = newDish(savedOutlet(), 8900);
        dish.describe("Crispy, with coconut chutney", clock);
        dish.changeDietaryType(DietaryType.EGG, clock);
        dish.changeImageRef("dishes/2026/abc123.webp", clock);
        dish.markUnavailable(clock);
        dish.trackStock(12, clock);
        dishes.saveAndFlush(dish);

        Dish loaded = dishes.findById(dish.getId()).orElseThrow();
        assertThat(loaded.getName()).isEqualTo(dish.getName());
        assertThat(loaded.getDescription()).isEqualTo("Crispy, with coconut chutney");
        assertThat(loaded.getDietaryType()).isEqualTo(DietaryType.EGG);
        assertThat(loaded.getImageRef()).isEqualTo("dishes/2026/abc123.webp");
        assertThat(loaded.isAvailable()).isFalse();
        assertThat(loaded.getStockQuantity()).isEqualTo(12);
        assertThat(loaded.getDeletedAt()).isNull();
        assertThat(jdbc.queryForObject("select dietary_type from dishes where id = ?", String.class, dish.getId()))
                .isEqualTo("EGG");
    }

    @Test
    void outletIsLoadedLazily() {
        Outlet outlet = savedOutlet();
        UUID dishId = dishes.saveAndFlush(newDish(outlet, 2500)).getId();

        tx.executeWithoutResult(s -> {
            entityManager.clear();
            Dish loaded = entityManager.find(Dish.class, dishId);
            assertThat(Hibernate.isInitialized(loaded.getOutlet())).isFalse();
            // Reading the id does not need the outlet row.
            assertThat(loaded.getOutletId()).isEqualTo(outlet.getId());
            assertThat(Hibernate.isInitialized(loaded.getOutlet())).isFalse();
            assertThat(loaded.getOutlet().getName()).isEqualTo(outlet.getName());
            assertThat(Hibernate.isInitialized(loaded.getOutlet())).isTrue();
        });
    }

    @Test
    void softDeleteKeepsTheRowHidesItFromTheMenuAndFreesTheName() {
        Outlet outlet = savedOutlet();
        Dish dish = dishes.saveAndFlush(newDish(outlet, 3000));
        Dish other = dishes.saveAndFlush(newDish(outlet, 3000));

        clock.advance(Duration.ofHours(1));
        dish.softDelete(clock);
        dishes.saveAndFlush(dish);

        assertThat(dishes.findById(dish.getId())).get().extracting(Dish::getDeletedAt).isEqualTo(T0.plus(Duration.ofHours(1)));
        assertThat(dishes.findByIdAndDeletedAtIsNull(dish.getId())).isEmpty();
        assertThat(dishes.findByOutlet_IdAndDeletedAtIsNullOrderByNameAsc(outlet.getId()))
                .extracting(Dish::getId).containsExactly(other.getId());

        Dish replacement = dishes.saveAndFlush(new Dish(outlet, dish.getName(), 3500, DietaryType.VEG, clock));
        assertThat(replacement.getId()).isNotEqualTo(dish.getId());
    }

    @Test
    void duplicateLiveNameIsRejectedThroughJpaToo() {
        Outlet outlet = savedOutlet();
        String name = unique("Samosa");
        dishes.saveAndFlush(new Dish(outlet, name, 1500, DietaryType.VEG, clock));

        assertThatThrownBy(() -> dishes.saveAndFlush(new Dish(outlet, name.toUpperCase(), 1500, DietaryType.VEG, clock)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void timestampsComeFromTheInjectedClock() {
        clock.set(T0.plusNanos(999_999_999));
        Dish dish = dishes.saveAndFlush(newDish(savedOutlet(), 1000));
        assertThat(dish.getCreatedAt()).isEqualTo(T0.plusNanos(999_999_000));

        clock.advance(Duration.ofDays(2));
        Dish reloaded = dishes.findById(dish.getId()).orElseThrow();
        reloaded.changePrice(1200, clock);
        dishes.saveAndFlush(reloaded);

        var row = jdbc.queryForMap("select created_at, updated_at, version from dishes where id = ?", dish.getId());
        assertThat(((Timestamp) row.get("created_at")).toInstant()).isEqualTo(T0.plusNanos(999_999_000));
        assertThat(((Timestamp) row.get("updated_at")).toInstant())
                .isEqualTo(T0.plusNanos(999_999_000).plus(Duration.ofDays(2)));
        assertThat(row.get("version")).isEqualTo(1L);
    }

    @Test
    void concurrentModificationIsDetectedByOptimisticLocking() {
        UUID id = dishes.saveAndFlush(newDish(savedOutlet(), 1000)).getId();
        Dish first = dishes.findById(id).orElseThrow();
        Dish stale = dishes.findById(id).orElseThrow();

        first.changePrice(1100, clock);
        dishes.saveAndFlush(first);
        stale.changePrice(1200, clock);

        assertThatThrownBy(() -> dishes.saveAndFlush(stale)).isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(dishes.findById(id).orElseThrow().getPricePaise()).isEqualTo(1100);
    }
}
