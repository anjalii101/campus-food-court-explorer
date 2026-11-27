package com.campusfoodcourt.campus_foodcourt.config;

import com.campusfoodcourt.campus_foodcourt.auth.AuthProperties;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Production must never send the refresh-token cookie without the Secure attribute.
 * Enforced in code because any property (including app.auth.cookie.secure) can be overridden
 * by an environment variable.
 */
@Component
@Profile("prod")
public class ProductionConfigurationGuard implements InitializingBean {

    private final AuthProperties authProperties;

    public ProductionConfigurationGuard(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    @Override
    public void afterPropertiesSet() {
        if (!authProperties.cookie().secure()) {
            throw new IllegalStateException("app.auth.cookie.secure must be true in the prod profile");
        }
    }
}
