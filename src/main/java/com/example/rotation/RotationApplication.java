package com.example.rotation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RotationApplication {

	public static void main(String[] args) {
		SpringApplication.run(RotationApplication.class, args);
	}
}

