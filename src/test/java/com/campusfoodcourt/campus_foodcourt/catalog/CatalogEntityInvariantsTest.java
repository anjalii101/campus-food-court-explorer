package com.campusfoodcourt.campus_foodcourt.catalog;

import com.campusfoodcourt.campus_foodcourt.menu.DietaryType;
import com.campusfoodcourt.campus_foodcourt.menu.Dish;
import com.campusfoodcourt.campus_foodcourt.outlets.IsoDayOfWeekConverter;
import com.campusfoodcourt.campus_foodcourt.outlets.OperatingHours;
import com.campusfoodcourt.campus_foodcourt.outlets.Outlet;
import com.campusfoodcourt.campus_foodcourt.reviews.Review;
import com.campusfoodcourt.campus_foodcourt.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Entity-level invariants that fail fast in Java, before anything reaches the database. No Spring context. */
class CatalogEntityInvariantsTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-05T04:30:00Z"));

    private Outlet outlet() {
        return new Outlet(1L, "Chai Point", "Main Gate", clock);
    }

    @Test
    void operatingHoursMustCloseAfterTheyOpenOnTheSameDay() {
        assertThatThrownBy(() -> new OperatingHours(LocalTime.of(18, 0), LocalTime.of(9, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OperatingHours(LocalTime.NOON, LocalTime.NOON))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OperatingHours(LocalTime.of(22, 0), LocalTime.of(2, 0)))
                .as("overnight intervals are out of scope").isInstanceOf(IllegalArgumentException.class);
        assertThat(new OperatingHours(LocalTime.of(9, 0), LocalTime.of(17, 0)).closesAt()).isEqualTo(LocalTime.of(17, 0));
        assertThat(OperatingHours.ZONE).isEqualTo(ZoneId.of("Asia/Kolkata"));
    }

    @Test
    void weekdaysUseIsoNumberingMondayOneToSundaySeven() {
        IsoDayOfWeekConverter converter = new IsoDayOfWeekConverter();
        assertThat(converter.convertToDatabaseColumn(DayOfWeek.MONDAY)).isEqualTo((short) 1);
        assertThat(converter.convertToDatabaseColumn(DayOfWeek.SUNDAY)).isEqualTo((short) 7);
        for (DayOfWeek day : DayOfWeek.values()) {
            assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(day))).isEqualTo(day);
        }
        assertThatThrownBy(() -> converter.convertToEntityAttribute((short) 0)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void settingADayAgainReplacesItsSingleInterval() {
        Outlet outlet = outlet();
        outlet.setHours(DayOfWeek.MONDAY, new OperatingHours(LocalTime.of(8, 0), LocalTime.of(12, 0)), clock);
        outlet.setHours(DayOfWeek.MONDAY, new OperatingHours(LocalTime.of(9, 0), LocalTime.of(17, 0)), clock);

        assertThat(outlet.getOperatingHours()).hasSize(1)
                .containsEntry(DayOfWeek.MONDAY, new OperatingHours(LocalTime.of(9, 0), LocalTime.of(17, 0)));
        assertThatThrownBy(() -> outlet.getOperatingHours().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void outletRequiresNameAndLocation() {
        assertThatThrownBy(() -> new Outlet(1L, " ", "Main Gate", clock)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Outlet(1L, "Chai Point", null, clock)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dishPriceMustBePositiveAndStockNotNegative() {
        assertThatThrownBy(() -> new Dish(outlet(), "Dosa", 0, DietaryType.VEG, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Dish(outlet(), "Dosa", -1, DietaryType.VEG, clock))
                .isInstanceOf(IllegalArgumentException.class);

        Dish dish = new Dish(outlet(), "Dosa", 1, DietaryType.VEG, clock);
        assertThat(dish.getStockQuantity()).as("stock is untracked by default").isNull();
        assertThat(dish.isAvailable()).isTrue();
        assertThatThrownBy(() -> dish.trackStock(-1, clock)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dish.changePrice(0, clock)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void softDeletedDishCannotBeModified() {
        Dish dish = new Dish(outlet(), "Dosa", 5000, DietaryType.VEG, clock);
        dish.softDelete(clock);

        assertThat(dish.isDeleted()).isTrue();
        assertThatThrownBy(() -> dish.changePrice(6000, clock)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> dish.markAvailable(clock)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reviewRatingMustBeBetweenOneAndFive() {
        Dish dish = new Dish(outlet(), "Dosa", 5000, DietaryType.VEG, clock);
        assertThatThrownBy(() -> new Review(1L, dish, 0, null, clock)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Review(1L, dish, 6, null, clock)).isInstanceOf(IllegalArgumentException.class);

        Review review = new Review(1L, dish, 5, null, clock);
        assertThat(review.getReviewText()).isNull();
        assertThatThrownBy(() -> review.revise(0, null, clock)).isInstanceOf(IllegalArgumentException.class);
        assertThat(review.getRating()).isEqualTo(5);
    }
}
