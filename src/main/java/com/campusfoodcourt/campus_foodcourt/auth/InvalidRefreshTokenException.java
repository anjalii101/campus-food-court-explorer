package com.campusfoodcourt.campus_foodcourt.auth;

/** The presented refresh token is unknown, expired, revoked or was reused after rotation. */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException(String message) {
        super(message);
    }
}
