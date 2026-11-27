package com.campusfoodcourt.campus_foodcourt;

import com.campusfoodcourt.campus_foodcourt.support.AbstractIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class CampusFoodcourtApplicationTests extends AbstractIntegrationTest {

	@Autowired
	private Flyway flyway;

	@Test
	void contextLoads() {
	}

	@Test
	void flywayAppliedAllMigrations() {
		assertThat(flyway.info().pending()).isEmpty();
		assertThat(flyway.info().applied()).extracting(m -> m.getVersion().getVersion())
				.containsExactly("1", "2", "3", "4", "5", "6");
	}

}
