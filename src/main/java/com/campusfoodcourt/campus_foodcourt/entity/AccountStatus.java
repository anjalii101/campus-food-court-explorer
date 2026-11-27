package com.campusfoodcourt.campus_foodcourt.entity;

/**
 * Whether a user may authenticate. Stored by name in users.account_status (V7 migration, check constraint
 * ck_users_account_status). Only {@link #ACTIVE} users can log in, refresh, or use an access token.
 */
public enum AccountStatus {
    /** Normal account. */
    ACTIVE,
    /** Temporarily blocked. */
    SUSPENDED,
    /** Closed. */
    DEACTIVATED
}
