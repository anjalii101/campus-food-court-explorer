package com.campusfoodcourt.campus_foodcourt.common;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Entity timestamps, always taken from the injected {@link Clock} rather than the system clock. */
public final class Timestamps {

    private Timestamps() {
    }

    /**
     * PostgreSQL TIMESTAMPTZ stores microseconds while {@link Instant} can carry nanoseconds. Truncating here keeps
     * the value held in memory identical to the value read back from the database.
     */
    public static Instant now(Clock clock) {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
