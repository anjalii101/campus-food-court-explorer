package com.campusfoodcourt.campus_foodcourt.outlets;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.DayOfWeek;

/**
 * Stores {@link DayOfWeek} as its ISO number (Monday = 1 ... Sunday = 7). Applied explicitly, never automatically:
 * the JPA default for enums would be the 0-based ordinal (Monday = 0), which the database CHECK rejects.
 */
@Converter
public class IsoDayOfWeekConverter implements AttributeConverter<DayOfWeek, Short> {

    @Override
    public Short convertToDatabaseColumn(DayOfWeek day) {
        return day == null ? null : (short) day.getValue();
    }

    @Override
    public DayOfWeek convertToEntityAttribute(Short isoDay) {
        return isoDay == null ? null : DayOfWeek.of(isoDay);
    }
}
