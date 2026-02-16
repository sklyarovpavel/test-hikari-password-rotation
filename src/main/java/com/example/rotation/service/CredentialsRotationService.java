package com.example.rotation.service;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@Service
public class CredentialsRotationService {
	private static final Logger log = LoggerFactory.getLogger(CredentialsRotationService.class);

	private final HikariDataSource hikariDataSource;
	private final JdbcTemplate jdbcTemplate;
	public CredentialsRotationService(DataSource dataSource, JdbcTemplate jdbcTemplate) {
		this.hikariDataSource = (HikariDataSource) dataSource;
		this.jdbcTemplate = jdbcTemplate;
	}

	public Map<String, Object> currentPoolInfo() {
		HikariPoolMXBean pool = hikariDataSource.getHikariPoolMXBean();
		Map<String, Object> info = new HashMap<>();
		info.put("activeConnections", pool.getActiveConnections());
		info.put("totalConnections", pool.getTotalConnections());
		info.put("idleConnections", pool.getIdleConnections());
		info.put("threadsAwaitingConnection", pool.getThreadsAwaitingConnection());
		return info;
	}

	@Transactional(readOnly = true)
	public String whoAmI() {
		return jdbcTemplate.queryForObject("select current_user", String.class);
	}

	// REST override removed per requirements; rotation happens via scheduler/file provider

	public synchronized void rotateCredentials(String username, String password) {
		String oldUser = hikariDataSource.getUsername();
		log.info("Applying credentials change: {} -> {}", oldUser, username);
		boolean updatedViaMxBean = tryUpdateViaConfigMxBean(username, password);
		if (!updatedViaMxBean) {
			log.debug("Falling back to direct DataSource setters for credentials");
			if (username != null) {
				hikariDataSource.setUsername(username);
			}
			if (password != null) {
				hikariDataSource.setPassword(password);
			}
		}
		validateNewConnection(username);
		softEvict();
		log.info("Credentials updated. New connections will use username={}", username);
	}

	private boolean tryUpdateViaConfigMxBean(String username, String password) {
		try {
			Object mx = hikariDataSource.getHikariConfigMXBean();
			boolean done = false;
			if (mx != null) {
				if (username != null) {
					Method m = mx.getClass().getMethod("setUsername", String.class);
					m.invoke(mx, username);
					done = true;
				}
				if (password != null) {
					Method m = mx.getClass().getMethod("setPassword", String.class);
					m.invoke(mx, password);
					done = true;
				}
			}
			return done;
		} catch (NoSuchMethodException e) {
			log.debug("MXBean does not expose setUsername/setPassword: {}", e.toString());
			return false;
		} catch (Exception e) {
			log.warn("Failed to update credentials via HikariConfigMXBean, will fallback. Cause: {}", e.toString());
			return false;
		}
	}

	private void validateNewConnection(String expectedUser) {
		try (Connection c = hikariDataSource.getConnection()) {
			String who = jdbcTemplate.queryForObject("select current_user", String.class);
			log.info("Validation query OK. current_user={}", who);
			if (expectedUser != null && !expectedUser.equalsIgnoreCase(who)) {
				log.warn("Expected current_user '{}' but got '{}'", expectedUser, who);
			}
		} catch (Exception e) {
			throw new IllegalStateException("Validation connection failed after credentials update: " + e.getMessage(), e);
		}
	}

	private void softEvict() {
		try {
			HikariPoolMXBean pool = hikariDataSource.getHikariPoolMXBean();
			pool.softEvictConnections();
			log.info("softEvictConnections invoked");
		} catch (Exception e) {
			log.warn("softEvictConnections failed: {}", e.toString());
		}
	}
}

