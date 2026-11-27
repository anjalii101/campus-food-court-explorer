package com.campusfoodcourt.campus_foodcourt.config;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.util.List;

/**
 * Checks every application DataSource as soon as it is created - before Flyway, JPA or any
 * repository can use it - so the protection does not depend on Flyway being enabled.
 */
@Configuration(proxyBeanMethods = false)
public class DatabaseSafetyConfig {

    @Bean
    static BeanPostProcessor protectedDatabaseGuard(Environment environment) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource dataSource) {
                    List<String> configured = Binder.get(environment)
                            .bind("app.database.protected-names", Bindable.listOf(String.class))
                            .orElse(List.of());
                    DatabaseSafetyGuard.verify(dataSource, configured);
                }
                return bean;
            }
        };
    }
}
