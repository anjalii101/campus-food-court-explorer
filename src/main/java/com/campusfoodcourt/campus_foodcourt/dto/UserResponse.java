package com.campusfoodcourt.campus_foodcourt.dto;

import com.campusfoodcourt.campus_foodcourt.entity.User;

/** Public view of a user. Never includes the password hash. */
public record UserResponse(Long id, String name, String email, String role) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getRole());
    }
}
