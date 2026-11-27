package com.campusfoodcourt.campus_foodcourt.common;

/** Small argument checks shared by entity methods. Request-level validation belongs to the API layer. */
public final class Preconditions {

    private Preconditions() {
    }

    public static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    /** Optional text: a blank value is stored as NULL so "absent" has exactly one representation. */
    public static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
