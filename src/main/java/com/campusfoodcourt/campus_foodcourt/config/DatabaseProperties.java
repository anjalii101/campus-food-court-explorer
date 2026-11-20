package com.campusfoodcourt.campus_foodcourt.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Additional databases the application must never use. "campusfoodcourt" is always protected
 * regardless of this list (see {@link DatabaseSafetyGuard#ALWAYS_PROTECTED}).
 */
@ConfigurationProperties(prefix = "app.database")
public record DatabaseProperties(List<String> protectedNames) {

    public DatabaseProperties {
        protectedNames = protectedNames == null ? List.of() : List.copyOf(protectedNames);
    }
}
