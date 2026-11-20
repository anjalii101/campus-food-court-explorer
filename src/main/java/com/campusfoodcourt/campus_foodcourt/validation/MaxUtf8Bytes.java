package com.campusfoodcourt.campus_foodcourt.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * The annotated string must encode to at most {@link #value()} bytes in UTF-8. Used for passwords,
 * because BCrypt only accepts up to 72 bytes and a character-count limit lets multi-byte text through.
 * {@code null} is valid (combine with {@code @NotBlank}).
 */
@Documented
@Constraint(validatedBy = MaxUtf8BytesValidator.class)
@Target({FIELD, METHOD, PARAMETER, ANNOTATION_TYPE})
@Retention(RUNTIME)
public @interface MaxUtf8Bytes {

    int value();

    String message() default "must be at most {value} bytes when UTF-8 encoded";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
