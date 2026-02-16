package com.example.rotation.service;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CredentialsPollingRefresher {
	private static final Logger log = LoggerFactory.getLogger(CredentialsPollingRefresher.class);

	private final Environment environment;
	private final HikariDataSource hikariDataSource;
	private final CredentialsRotationService rotationService;

	private volatile String lastAppliedUsername;
	private volatile String lastAppliedPassword;

	public CredentialsPollingRefresher(Environment environment, HikariDataSource hikariDataSource, CredentialsRotationService rotationService) {
		this.environment = environment;
		this.hikariDataSource = hikariDataSource;
		this.rotationService = rotationService;
	}

	@PostConstruct
	public void init() {
		this.lastAppliedUsername = hikariDataSource.getUsername();
		this.lastAppliedPassword = null; // пароль не читается из пула; будем сравнивать с последним применённым
		log.info("CredentialsPollingRefresher initialized. Current username={}", lastAppliedUsername);
	}

	@Scheduled(fixedDelay = 60000, initialDelay = 60000)
	public void pollEnvironmentForCredentialsChange() {
		try {
			String envUser = environment.getProperty("spring.datasource.username");
			String envPass = environment.getProperty("spring.datasource.password");
			if (envUser == null && envPass == null) {
				return;
			}
			boolean userChanged = envUser != null && !envUser.equals(lastAppliedUsername);
			boolean passChanged = envPass != null && (lastAppliedPassword == null || !envPass.equals(lastAppliedPassword));
			if (userChanged || passChanged) {
				log.info("Detected credentials change in Environment: username {} -> {}", lastAppliedUsername, envUser);
				rotationService.rotateCredentials(envUser != null ? envUser : lastAppliedUsername, envPass);
				lastAppliedUsername = envUser != null ? envUser : lastAppliedUsername;
				lastAppliedPassword = envPass != null ? envPass : lastAppliedPassword;
			}
		} catch (Exception e) {
			log.warn("Credentials polling failed: {}", e.toString());
		}
	}
}

