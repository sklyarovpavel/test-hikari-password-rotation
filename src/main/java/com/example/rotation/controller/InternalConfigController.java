package com.example.rotation.controller;

import com.example.rotation.service.CredentialsRotationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/internal/config")
public class InternalConfigController {

	private final CredentialsRotationService rotationService;

	public InternalConfigController(CredentialsRotationService rotationService) {
		this.rotationService = rotationService;
	}

	@PostMapping("/override")
	public ResponseEntity<Map<String, Object>> override(@RequestBody Map<String, String> body) {
		rotationService.applySpringDatasourceOverrides(body);
		return ResponseEntity.ok(Map.of(
			"status", "applied",
			"pool", rotationService.currentPoolInfo()
		));
	}
}

