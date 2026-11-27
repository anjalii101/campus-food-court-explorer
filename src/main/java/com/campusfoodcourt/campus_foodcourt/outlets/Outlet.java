package com.campusfoodcourt.campus_foodcourt.outlets;

import com.campusfoodcourt.campus_foodcourt.common.Preconditions;
import com.campusfoodcourt.campus_foodcourt.common.Timestamps;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A food outlet, owned by exactly one vendor user (table outlets, migration V4).
 * <p>
 * Dishes reference their outlet, but the outlet deliberately has no collection of dishes: a menu is loaded through
 * {@code DishRepository}, and deleting an outlet never cascades to dishes.
 */
@Entity
@Table(name = "outlets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Outlet {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** users.id of the owning vendor. A plain id: this module never loads the User entity or its password hash. */
    @Column(name = "vendor_user_id", nullable = false, updatable = false)
    private Long vendorUserId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "location", nullable = false, length = 200)
    private String location;

    @Column(name = "contact_phone", length = 20)
    private String contactPhone;

    @Column(name = "contact_email", length = 255)
    private String contactEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OutletStatus status;

    /** Temporarily closed by the vendor, regardless of operating hours. */
    @Column(name = "manually_closed", nullable = false)
    private boolean manuallyClosed;

    /**
     * Value collection owned by this outlet: one interval per weekday, enforced by the map key here and by the
     * primary key (outlet_id, day_of_week) in the database. A missing day means closed. Loaded lazily.
     */
    @Getter(AccessLevel.NONE)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "outlet_operating_hours", joinColumns = @JoinColumn(name = "outlet_id"))
    @MapKeyColumn(name = "day_of_week")
    @Convert(converter = IsoDayOfWeekConverter.class, attributeName = "key")
    private Map<DayOfWeek, OperatingHours> operatingHours = new HashMap<>();

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Outlet(long vendorUserId, String name, String location, Clock clock) {
        this.vendorUserId = vendorUserId;
        this.name = Preconditions.requireNonBlank(name, "name");
        this.location = Preconditions.requireNonBlank(location, "location");
        this.status = OutletStatus.ACTIVE;
        this.manuallyClosed = false;
        this.createdAt = Timestamps.now(clock);
        this.updatedAt = this.createdAt;
    }

    public void rename(String newName, Clock clock) {
        this.name = Preconditions.requireNonBlank(newName, "name");
        touch(clock);
    }

    public void updateDetails(String description, String location, String contactPhone, String contactEmail,
                              Clock clock) {
        this.description = Preconditions.blankToNull(description);
        this.location = Preconditions.requireNonBlank(location, "location");
        this.contactPhone = Preconditions.blankToNull(contactPhone);
        this.contactEmail = Preconditions.blankToNull(contactEmail);
        touch(clock);
    }

    public void suspend(Clock clock) {
        this.status = OutletStatus.SUSPENDED;
        touch(clock);
    }

    public void reinstate(Clock clock) {
        this.status = OutletStatus.ACTIVE;
        touch(clock);
    }

    public void closeManually(Clock clock) {
        this.manuallyClosed = true;
        touch(clock);
    }

    public void reopenManually(Clock clock) {
        this.manuallyClosed = false;
        touch(clock);
    }

    /** Sets (or replaces) the single opening interval of {@code day}. */
    public void setHours(DayOfWeek day, OperatingHours hours, Clock clock) {
        operatingHours.put(Objects.requireNonNull(day, "day"), Objects.requireNonNull(hours, "hours"));
        touch(clock);
    }

    /** Marks the outlet as closed all day on {@code day}. */
    public void closeOn(DayOfWeek day, Clock clock) {
        operatingHours.remove(Objects.requireNonNull(day, "day"));
        touch(clock);
    }

    /** Empty when the outlet is closed on that day. */
    public Optional<OperatingHours> hoursOn(DayOfWeek day) {
        return Optional.ofNullable(operatingHours.get(day));
    }

    /** Read-only copy ordered Monday to Sunday; closed days are absent. */
    public Map<DayOfWeek, OperatingHours> getOperatingHours() {
        EnumMap<DayOfWeek, OperatingHours> copy = new EnumMap<>(DayOfWeek.class);
        copy.putAll(operatingHours);
        return Collections.unmodifiableMap(copy);
    }

    private void touch(Clock clock) {
        this.updatedAt = Timestamps.now(clock);
    }
}
