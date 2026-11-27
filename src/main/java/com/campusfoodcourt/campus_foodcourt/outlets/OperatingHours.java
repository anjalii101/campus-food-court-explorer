package com.campusfoodcourt.campus_foodcourt.outlets;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;

/**
 * The single opening interval of an outlet on one weekday (a row of outlet_operating_hours; the weekday is the key
 * of {@link Outlet#getOperatingHours()}). Times are wall-clock times in {@link #ZONE}. The interval must close on the
 * same day it opens: overnight intervals and split shifts are not supported.
 */
@Embeddable
public record OperatingHours(
        @Column(name = "opens_at", nullable = false) LocalTime opensAt,
        @Column(name = "closes_at", nullable = false) LocalTime closesAt) {

    /** Time zone in which opening and closing times are interpreted. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    public OperatingHours {
        Objects.requireNonNull(opensAt, "opensAt");
        Objects.requireNonNull(closesAt, "closesAt");
        if (!closesAt.isAfter(opensAt)) {
            throw new IllegalArgumentException("closesAt must be after opensAt on the same day (overnight hours are"
                    + " not supported): " + opensAt + "-" + closesAt);
        }
    }
}
