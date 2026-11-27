package com.campusfoodcourt.campus_foodcourt.dto;

import com.campusfoodcourt.campus_foodcourt.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Registration input. Only these fields are accepted, so clients cannot set id or role. */
public record RegisterRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Email @Size(max = 255) String email,
        // BCrypt accepts at most 72 bytes; the limit is on UTF-8 bytes, not characters.
        @NotBlank @Size(min = 8) @MaxUtf8Bytes(72) String password) {
}
