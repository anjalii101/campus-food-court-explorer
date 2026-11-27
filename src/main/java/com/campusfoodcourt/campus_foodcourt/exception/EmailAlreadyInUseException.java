package com.campusfoodcourt.campus_foodcourt.exception;

public class EmailAlreadyInUseException extends RuntimeException {

    public EmailAlreadyInUseException() {
        super("An account with this email already exists");
    }
}
