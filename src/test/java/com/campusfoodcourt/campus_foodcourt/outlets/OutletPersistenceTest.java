package com.campusfoodcourt.campus_foodcourt.outlets;

import com.campusfoodcourt.campus_foodcourt.support.AbstractCatalogIntegrationTest;
import com.campusfoodcourt.campus_foodcourt.support.MutableClock;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** JPA mapping of {@link Outlet} and its operating hours against the V4 schema. */
class OutletPersistenceTest extends AbstractCatalogIntegrationTest {

    @Autowired
    private OutletRepository outlets;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private final MutableClock clock = new MutableClock(T0);

    private Outlet newOutlet() {
        return new Outlet(insertUser(), unique("Outlet"), "Library Lawn", clock);
    }

    @Test
    void idIsAGeneratedRandomUuidStoredInAUuidColumn() {
        Outlet outlet = newOutlet();
        assertThat(outlet.getId()).isNull();

        outlets.saveAndFlush(outlet);

        assertThat(outlet.getId()).isNotNull();
        assertThat(outlet.getId().version()).isEqualTo(4);
        assertThat(jdbc.queryForObject("select pg_typeof(id)::text from outlets where id = ?", String.class,
                outlet.getId())).isEqualTo("uuid");
        assertThat(outlet.getVersion()).isZero();
    }

    @Test
    void newOutletIsActiveOpenAndPersistsAllFields() {
        Outlet outlet = newOutlet();
        outlet.updateDetails("Filter coffee and snacks", "Main Gate", "+91 98765 43210", "chai@campus.test", clock);
        outlets.saveAndFlush(outlet);

        Outlet loaded = outlets.findById(outlet.getId()).orElseThrow();
        assertThat(loaded.getVendorUserId()).isEqualTo(outlet.getVendorUserId());
        assertThat(loaded.getName()).isEqualTo(outlet.getName());
        assertThat(loaded.getDescription()).isEqualTo("Filter coffee and snacks");
        assertThat(loaded.getLocation()).isEqualTo("Main Gate");
        assertThat(loaded.getContactPhone()).isEqualTo("+91 98765 43210");
        assertThat(loaded.getContactEmail()).isEqualTo("chai@campus.test");
        assertThat(loaded.getStatus()).isEqualTo(OutletStatus.ACTIVE);
        assertThat(loaded.isManuallyClosed()).isFalse();
        assertThat(outlets.findByVendorUserId(outlet.getVendorUserId())).map(Outlet::getId).contains(outlet.getId());
    }

    @Test
    void statusAndManualClosureRoundTrip() {
        Outlet outlet = outlets.saveAndFlush(newOutlet());
        outlet.suspend(clock);
        outlet.closeManually(clock);
        outlets.saveAndFlush(outlet);

        assertThat(jdbc.queryForMap("select status, manually_closed from outlets where id = ?", outlet.getId()))
                .containsEntry("status", "SUSPENDED").containsEntry("manually_closed", true);
    }

    @Test
    void timestampsComeFromTheInjectedClockAndRoundTripExactly() {
        clock.set(T0.plusNanos(123_456_789)); // sub-microsecond part must not cause a mismatch
        Outlet outlet = outlets.saveAndFlush(newOutlet());
        Instant created = T0.plusNanos(123_456_000);
        assertThat(outlet.getCreatedAt()).isEqualTo(created);
        assertThat(outlet.getUpdatedAt()).isEqualTo(created);

        clock.advance(Duration.ofMinutes(5));
        Outlet reloaded = outlets.findById(outlet.getId()).orElseThrow();
        reloaded.rename(unique("Renamed"), clock);
        outlets.saveAndFlush(reloaded);

        Map<String, Object> row = jdbc.queryForMap("select created_at, updated_at from outlets where id = ?",
                outlet.getId());
        assertThat(((Timestamp) row.get("created_at")).toInstant()).isEqualTo(created);
        assertThat(((Timestamp) row.get("updated_at")).toInstant()).isEqualTo(created.plus(Duration.ofMinutes(5)));
        assertThat(outlets.findById(outlet.getId()).orElseThrow().getCreatedAt()).isEqualTo(created);
    }

    @Test
    void operatingHoursAreStoredWithIsoWeekdayNumbersAndMissingDaysMeanClosed() {
        Outlet outlet = newOutlet();
        outlet.setHours(DayOfWeek.MONDAY, new OperatingHours(LocalTime.of(8, 0), LocalTime.of(20, 0)), clock);
        outlet.setHours(DayOfWeek.SUNDAY, new OperatingHours(LocalTime.of(10, 30), LocalTime.of(14, 0)), clock);
        outlets.saveAndFlush(outlet);

        // PgJDBC returns SMALLINT values as Integer.
        List<Map<String, Object>> rows = jdbc.queryForList("select day_of_week, opens_at::text as opens,"
                + " closes_at::text as closes from outlet_operating_hours where outlet_id = ? order by day_of_week",
                outlet.getId());
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("day_of_week", 1).containsEntry("opens", "08:00:00")
                .containsEntry("closes", "20:00:00");
        assertThat(rows.get(1)).containsEntry("day_of_week", 7).containsEntry("opens", "10:30:00")
                .containsEntry("closes", "14:00:00");

        Outlet loaded = tx.execute(s -> {
            Outlet o = outlets.findById(outlet.getId()).orElseThrow();
            o.getOperatingHours().size(); // initialise inside the transaction
            return o;
        });
        assertThat(loaded.getOperatingHours().keySet()).containsExactly(DayOfWeek.MONDAY, DayOfWeek.SUNDAY);
        assertThat(loaded.hoursOn(DayOfWeek.MONDAY))
                .contains(new OperatingHours(LocalTime.of(8, 0), LocalTime.of(20, 0)));
        assertThat(loaded.hoursOn(DayOfWeek.TUESDAY)).isEmpty();
    }

    @Test
    void replacingOrRemovingADayKeepsOneIntervalPerDay() {
        Outlet outlet = newOutlet();
        outlet.setHours(DayOfWeek.WEDNESDAY, new OperatingHours(LocalTime.of(8, 0), LocalTime.of(12, 0)), clock);
        outlet.setHours(DayOfWeek.THURSDAY, new OperatingHours(LocalTime.of(8, 0), LocalTime.of(12, 0)), clock);
        outlets.saveAndFlush(outlet);

        tx.executeWithoutResult(s -> {
            Outlet o = outlets.findById(outlet.getId()).orElseThrow();
            o.setHours(DayOfWeek.WEDNESDAY, new OperatingHours(LocalTime.of(9, 0), LocalTime.of(18, 0)), clock);
            o.closeOn(DayOfWeek.THURSDAY, clock);
        });

        assertThat(jdbc.queryForList("select day_of_week, opens_at::text as opens from outlet_operating_hours"
                + " where outlet_id = ?", outlet.getId()))
                .singleElement()
                .satisfies(r -> assertThat(r).containsEntry("day_of_week", 3).containsEntry("opens", "09:00:00"));
    }

    @Test
    void operatingHoursAreLoadedLazily() {
        Outlet outlet = newOutlet();
        outlet.setHours(DayOfWeek.FRIDAY, new OperatingHours(LocalTime.of(9, 0), LocalTime.of(17, 0)), clock);
        outlets.saveAndFlush(outlet);

        tx.executeWithoutResult(s -> {
            entityManager.clear();
            Outlet loaded = entityManager.find(Outlet.class, outlet.getId());
            var util = entityManagerFactory.getPersistenceUnitUtil();
            assertThat(util.isLoaded(loaded, "operatingHours")).isFalse();
            assertThat(loaded.hoursOn(DayOfWeek.FRIDAY)).isPresent();
            assertThat(util.isLoaded(loaded, "operatingHours")).isTrue();
        });
    }

    @Test
    void concurrentModificationIsDetectedByOptimisticLocking() {
        UUID id = outlets.saveAndFlush(newOutlet()).getId();
        Outlet first = outlets.findById(id).orElseThrow();
        Outlet stale = outlets.findById(id).orElseThrow();

        first.rename(unique("First writer"), clock);
        outlets.saveAndFlush(first);
        assertThat(outlets.findById(id).orElseThrow().getVersion()).isEqualTo(1L);

        stale.rename(unique("Second writer"), clock);
        assertThatThrownBy(() -> outlets.saveAndFlush(stale)).isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void secondOutletForTheSameVendorIsRejectedThroughJpaToo() {
        long vendor = insertUser();
        outlets.saveAndFlush(new Outlet(vendor, unique("One"), "Hostel 1", clock));

        assertThatThrownBy(() -> outlets.saveAndFlush(new Outlet(vendor, unique("Two"), "Hostel 2", clock)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select count(*) from outlets where vendor_user_id = ?", Long.class, vendor))
                .isOne();
    }
}
