package com.campusfoodcourt.campus_foodcourt.menu;

import com.campusfoodcourt.campus_foodcourt.common.Preconditions;
import com.campusfoodcourt.campus_foodcourt.common.Timestamps;
import com.campusfoodcourt.campus_foodcourt.outlets.Outlet;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A dish on an outlet's menu (table dishes, migration V5). */
@Entity
@Table(name = "dishes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Dish {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Lazy: loading a dish never loads its outlet unless the outlet is actually used. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "outlet_id", nullable = false, updatable = false)
    private Outlet outlet;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", length = 1000)
    private String description;

    /** Exact price in paise (Rs 1 = 100 paise). Always > 0. */
    @Column(name = "price_paise", nullable = false)
    private long pricePaise;

    @Enumerated(EnumType.STRING)
    @Column(name = "dietary_type", nullable = false, length = 10)
    private DietaryType dietaryType;

    @Column(name = "available", nullable = false)
    private boolean available;

    /** NULL means stock is not tracked for this dish; 0 means tracked and sold out. */
    @Column(name = "stock_quantity")
    private Integer stockQuantity;

    /** Storage key of the dish image, not a public URL. */
    @Column(name = "image_ref", length = 500)
    private String imageRef;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Dish(Outlet outlet, String name, long pricePaise, DietaryType dietaryType, Clock clock) {
        this.outlet = Objects.requireNonNull(outlet, "outlet");
        this.name = Preconditions.requireNonBlank(name, "name");
        this.pricePaise = requirePositivePrice(pricePaise);
        this.dietaryType = Objects.requireNonNull(dietaryType, "dietaryType");
        this.available = true;
        this.createdAt = Timestamps.now(clock);
        this.updatedAt = this.createdAt;
    }

    /** Id of the owning outlet, readable without initialising the lazy outlet proxy. */
    public UUID getOutletId() {
        return outlet.getId();
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public void rename(String newName, Clock clock) {
        requireNotDeleted();
        this.name = Preconditions.requireNonBlank(newName, "name");
        touch(clock);
    }

    public void describe(String newDescription, Clock clock) {
        requireNotDeleted();
        this.description = Preconditions.blankToNull(newDescription);
        touch(clock);
    }

    public void changePrice(long newPricePaise, Clock clock) {
        requireNotDeleted();
        this.pricePaise = requirePositivePrice(newPricePaise);
        touch(clock);
    }

    public void changeDietaryType(DietaryType newDietaryType, Clock clock) {
        requireNotDeleted();
        this.dietaryType = Objects.requireNonNull(newDietaryType, "dietaryType");
        touch(clock);
    }

    public void markAvailable(Clock clock) {
        requireNotDeleted();
        this.available = true;
        touch(clock);
    }

    public void markUnavailable(Clock clock) {
        requireNotDeleted();
        this.available = false;
        touch(clock);
    }

    /** Starts (or updates) stock tracking with the given quantity. */
    public void trackStock(int quantity, Clock clock) {
        requireNotDeleted();
        if (quantity < 0) {
            throw new IllegalArgumentException("stock quantity must not be negative: " + quantity);
        }
        this.stockQuantity = quantity;
        touch(clock);
    }

    /** Stops tracking stock (stored as NULL, which is different from 0). */
    public void stopTrackingStock(Clock clock) {
        requireNotDeleted();
        this.stockQuantity = null;
        touch(clock);
    }

    public void changeImageRef(String newImageRef, Clock clock) {
        requireNotDeleted();
        this.imageRef = Preconditions.blankToNull(newImageRef);
        touch(clock);
    }

    /** Soft deletion: the row stays (reviews keep pointing at it) and its name becomes free for reuse. */
    public void softDelete(Clock clock) {
        if (isDeleted()) {
            return;
        }
        this.deletedAt = Timestamps.now(clock);
        this.updatedAt = this.deletedAt;
    }

    private static long requirePositivePrice(long paise) {
        if (paise <= 0) {
            throw new IllegalArgumentException("price must be greater than zero paise: " + paise);
        }
        return paise;
    }

    private void requireNotDeleted() {
        if (isDeleted()) {
            throw new IllegalStateException("dish " + id + " is deleted");
        }
    }

    private void touch(Clock clock) {
        this.updatedAt = Timestamps.now(clock);
    }
}
