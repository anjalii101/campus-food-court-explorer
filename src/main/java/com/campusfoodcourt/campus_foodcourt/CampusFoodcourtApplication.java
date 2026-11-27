package com.campusfoodcourt.campus_foodcourt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CampusFoodcourtApplication {

	public static void main(String[] args) {
		SpringApplication.run(CampusFoodcourtApplication.class, args);
	}

}
