package com.campusfoodcourt.campus_foodcourt.outlets;

/** Administrative status of an outlet (database CHECK ck_outlets_status). Separate from "manually closed". */
public enum OutletStatus {
    ACTIVE,
    SUSPENDED
}
