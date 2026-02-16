package com.example.rotation.controller;

import com.example.rotation.service.CredentialsRotationService;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/db")
public class DbController {

	private final JdbcTemplate jdbcTemplate;
	private final CredentialsRotationService rotationService;

	public DbController(JdbcTemplate jdbcTemplate, CredentialsRotationService rotationService) {
		this.jdbcTemplate = jdbcTemplate;
		this.rotationService = rotationService;
	}

	@GetMapping("/ping")
	public ResponseEntity<Map<String, Object>> ping() {
		Integer one = jdbcTemplate.queryForObject("select 1", Integer.class);
		return ResponseEntity.ok(Map.of("ok", one != null && one == 1, "pool", rotationService.currentPoolInfo()));
	}

	@GetMapping("/whoami")
	public ResponseEntity<Map<String, Object>> whoami() {
		String who = rotationService.whoAmI();
		return ResponseEntity.ok(Map.of("current_user", who, "pool", rotationService.currentPoolInfo()));
	}
}

