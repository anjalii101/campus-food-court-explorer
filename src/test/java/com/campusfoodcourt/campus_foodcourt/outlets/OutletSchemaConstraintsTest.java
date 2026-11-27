package com.campusfoodcourt.campus_foodcourt.outlets;

import com.campusfoodcourt.campus_foodcourt.support.AbstractCatalogIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Database-level rules of V4 (outlets, outlet_operating_hours), exercised with plain SQL - no JPA checks involved. */
class OutletSchemaConstraintsTest extends AbstractCatalogIntegrationTest {

    // ---- ownership -------------------------------------------------------------------------------------------

    @Test
    void aVendorCanOwnAtMostOneOutlet() {
        long vendor = insertUser();
        insertOutlet(vendor, unique("First"));

        assertViolates(UNIQUE_VIOLATION, "uk_outlets_vendor_user_id", () -> insertOutlet(vendor, unique("Second")));
    }

    @Test
    void outletMustReferenceAnExistingUser() {
        assertViolates(FOREIGN_KEY_VIOLATION, "fk_outlets_vendor_user", () -> insertOutlet(-42L, unique("Orphan")));
    }

    @Test
    void deletingAVendorWhoOwnsAnOutletIsRestricted() {
        long vendor = insertUser();
        UUID outlet = insertOutlet(vendor, unique("Owned"));

        assertViolates(FOREIGN_KEY_VIOLATION, "fk_outlets_vendor_user",
                () -> jdbc.update("delete from users where id = ?", vendor));
        assertThat(jdbc.queryForObject("select count(*) from outlets where id = ?", Long.class, outlet)).isOne();
    }

    // ---- name ------------------------------------------------------------------------------------------------

    @Test
    void outletNamesAreUniqueIgnoringCase() {
        String name = unique("Chai Point");
        insertOutlet(insertUser(), name);

        assertViolates(UNIQUE_VIOLATION, "uk_outlets_name_lower", () -> insertOutlet(insertUser(), name.toUpperCase()));
        assertViolates(UNIQUE_VIOLATION, "uk_outlets_name_lower", () -> insertOutlet(insertUser(), name.toLowerCase()));
    }

    @Test
    void requiredColumnsAndLengthsAreEnforced() {
        long vendor = insertUser();
        assertViolates(NOT_NULL_VIOLATION, null, () -> insertOutlet(vendor, null));
        assertViolates(STRING_TOO_LONG, null, () -> insertOutlet(vendor, "x".repeat(101)));
        assertViolates(NOT_NULL_VIOLATION, null, () -> jdbc.update("insert into outlets (id, vendor_user_id, name,"
                + " status, created_at, updated_at) values (gen_random_uuid(), ?, ?, 'ACTIVE', now(), now())",
                vendor, unique("No location")));
    }

    // ---- status, flags, timestamps ---------------------------------------------------------------------------

    @Test
    void statusIsRestrictedToActiveAndSuspended() {
        UUID outlet = insertOutlet();
        jdbc.update("update outlets set status = 'SUSPENDED' where id = ?", outlet);

        assertViolates(CHECK_VIOLATION, "ck_outlets_status",
                () -> jdbc.update("update outlets set status = 'CLOSED' where id = ?", outlet));
        assertViolates(CHECK_VIOLATION, "ck_outlets_status",
                () -> jdbc.update("update outlets set status = 'active' where id = ?", outlet));
    }

    @Test
    void manuallyClosedDefaultsToFalse() {
        UUID outlet = insertOutlet();
        assertThat(jdbc.queryForObject("select manually_closed from outlets where id = ?", Boolean.class, outlet))
                .isFalse();
    }

    @Test
    void updatedAtCannotBeBeforeCreatedAt() {
        UUID outlet = insertOutlet();
        assertViolates(CHECK_VIOLATION, "ck_outlets_updated_after_created", () -> jdbc.update(
                "update outlets set updated_at = created_at - interval '1 second' where id = ?", outlet));
    }

    // ---- operating hours ---------------------------------------------------------------------------------------

    private int insertHours(UUID outlet, int day, String opens, String closes) {
        return jdbc.update("insert into outlet_operating_hours (outlet_id, day_of_week, opens_at, closes_at)"
                + " values (?, ?, ?::time, ?::time)", outlet, day, opens, closes);
    }

    @Test
    void isoWeekdaysOneToSevenAreAcceptedAndNothingElse() {
        UUID outlet = insertOutlet();
        for (int day = 1; day <= 7; day++) {
            assertThat(insertHours(outlet, day, "09:00", "17:00")).isOne();
        }
        UUID other = insertOutlet();
        assertViolates(CHECK_VIOLATION, "ck_outlet_operating_hours_day_of_week", () -> insertHours(other, 0, "09:00", "17:00"));
        assertViolates(CHECK_VIOLATION, "ck_outlet_operating_hours_day_of_week", () -> insertHours(other, 8, "09:00", "17:00"));
        assertViolates(CHECK_VIOLATION, "ck_outlet_operating_hours_day_of_week", () -> insertHours(other, -1, "09:00", "17:00"));
    }

    @Test
    void closingTimeMustBeAfterOpeningTime() {
        UUID outlet = insertOutlet();
        assertViolates(CHECK_VIOLATION, "ck_outlet_operating_hours_closes_after_opens",
                () -> insertHours(outlet, 1, "17:00", "09:00"));
        assertViolates(CHECK_VIOLATION, "ck_outlet_operating_hours_closes_after_opens",
                () -> insertHours(outlet, 1, "09:00", "09:00"));
        // Overnight intervals are out of scope: 22:00-02:00 is rejected, not interpreted as crossing midnight.
        assertViolates(CHECK_VIOLATION, "ck_outlet_operating_hours_closes_after_opens",
                () -> insertHours(outlet, 1, "22:00", "02:00"));
        assertThat(insertHours(outlet, 1, "00:00", "23:59:59")).isOne();
    }

    @Test
    void onlyOneIntervalPerOutletAndDay() {
        UUID outlet = insertOutlet();
        insertHours(outlet, 3, "08:00", "12:00");

        // A split shift on the same day would be a second row for (outlet, day): rejected by the primary key.
        assertViolates(UNIQUE_VIOLATION, "pk_outlet_operating_hours", () -> insertHours(outlet, 3, "14:00", "18:00"));
        // The same day for a different outlet is fine.
        assertThat(insertHours(insertOutlet(), 3, "08:00", "12:00")).isOne();
    }

    @Test
    void operatingHoursMustBelongToAnExistingOutlet() {
        assertViolates(FOREIGN_KEY_VIOLATION, "fk_outlet_operating_hours_outlet",
                () -> insertHours(UUID.randomUUID(), 1, "09:00", "17:00"));
    }

    @Test
    void deletingAnOutletWithoutDishesDeletesOnlyItsOwnHours() {
        UUID doomed = insertOutlet();
        UUID kept = insertOutlet();
        insertHours(doomed, 1, "09:00", "17:00");
        insertHours(doomed, 2, "09:00", "17:00");
        insertHours(kept, 1, "09:00", "17:00");

        jdbc.update("delete from outlets where id = ?", doomed);

        assertThat(jdbc.queryForObject("select count(*) from outlet_operating_hours where outlet_id = ?", Long.class,
                doomed)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from outlet_operating_hours where outlet_id = ?", Long.class,
                kept)).isOne();
    }
}
