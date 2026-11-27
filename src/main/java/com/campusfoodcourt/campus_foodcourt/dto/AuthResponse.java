package com.campusfoodcourt.campus_foodcourt.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;

import java.time.Instant;

/**
 * Result of register, login and refresh.
 * {@code token} is the short-lived JWT access token (field name kept from the original DTO).
 * {@code refreshToken} and its expiry are carried to the controller, which puts them in an HttpOnly
 * cookie; they are never serialised into the JSON body, so scripts cannot read them.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {
    private String token;
    private String tokenType;
    private long expiresIn;
    @JsonIgnore
    private String refreshToken;
    @JsonIgnore
    private Instant refreshTokenExpiresAt;
}
