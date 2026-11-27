package com.campusfoodcourt.campus_foodcourt.auth;

/** Why a refresh token stopped being valid. Only replay of a ROTATED token indicates possible theft. */
public enum RevocationReason {
    /** Exchanged for a successor during a normal refresh. */
    ROTATED,
    /** The session was ended by logout. */
    LOGOUT,
    /** A previously rotated token was presented again; the whole family was revoked. */
    REUSE_DETECTED,
    /** The user's account was deactivated; every active session of the user was revoked (V8 migration). */
    ACCOUNT_DEACTIVATED
}
