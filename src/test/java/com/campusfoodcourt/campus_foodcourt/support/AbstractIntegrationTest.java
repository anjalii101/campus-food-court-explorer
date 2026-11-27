package com.campusfoodcourt.campus_foodcourt.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Full application context against the embedded throwaway PostgreSQL, with Flyway migrations applied. */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDatabase::jdbcUrl);
        registry.add("spring.datasource.username", () -> TestDatabase.USERNAME);
        registry.add("spring.datasource.password", () -> TestDatabase.PASSWORD);
    }
}
