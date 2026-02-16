package com.example.rotation.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.UrlResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URL;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;

@Component
public class CredentialsPollingRefresher implements InitializingBean {
	private static final Logger log = LoggerFactory.getLogger(CredentialsPollingRefresher.class);
	private static final String RELOADED_SOURCE = "reloadedConfig";

	private final ConfigurableEnvironment environment;
	private final CredentialsRotationService rotationService;

	private volatile String lastAppliedUsername;
	private volatile String lastAppliedPassword;
	private Map<String, Object> backing = new HashMap<>();

	public CredentialsPollingRefresher(ConfigurableEnvironment environment,
	                                   CredentialsRotationService rotationService) {
		this.environment = environment;
		this.rotationService = rotationService;
	}

	@Override
	public void afterPropertiesSet() {
		MutablePropertySources sources = environment.getPropertySources();
		if (!sources.contains(RELOADED_SOURCE)) {
			sources.addFirst(new MapPropertySource(RELOADED_SOURCE, backing));
		}
	}

	@PostConstruct
	public void init() {
		this.lastAppliedUsername = environment.getProperty("spring.datasource.username");
		this.lastAppliedPassword = environment.getProperty("spring.datasource.password");
		log.info("CredentialsPollingRefresher initialized. Current username={}", lastAppliedUsername);
	}

	@Scheduled(fixedDelay = 60000, initialDelay = 60000)
	public void reloadAndApplyIfChanged() {
		try {
			log.info("Polling configuration for datasource credential changes...");
			Properties loaded = loadFromStandardConfigLocations();
			if (loaded.isEmpty()) {
				log.info("Polling complete: no datasource credentials found in available config sources");
				return;
			}
			String newUser = loaded.getProperty("spring.datasource.username");
			String newPass = loaded.getProperty("spring.datasource.password");
			if (newUser == null && newPass == null) {
				log.info("Polling complete: keys spring.datasource.username/password are absent");
				return;
			}
			log.info("Read candidate credentials from config: username={}, password={}", newUser, (newPass != null ? "***" : "<unchanged/absent>"));

			boolean userChanged = newUser != null && !newUser.equals(lastAppliedUsername);
			boolean passChanged = newPass != null && (lastAppliedPassword == null || !newPass.equals(lastAppliedPassword));
			if (userChanged || passChanged) {
				log.info("Detected credentials change in config: username {} -> {}", lastAppliedUsername, newUser);
				// обновим PropertySource, чтобы Environment отражал новые стандартные ключи
				if (newUser != null) backing.put("spring.datasource.username", newUser);
				if (newPass != null) backing.put("spring.datasource.password", newPass);

				rotationService.rotateCredentials(newUser != null ? newUser : lastAppliedUsername, newPass);
				lastAppliedUsername = newUser != null ? newUser : lastAppliedUsername;
				lastAppliedPassword = newPass != null ? newPass : lastAppliedPassword;
			} else {
				log.info("No credential changes detected. Current username={}, keeping existing pool settings", lastAppliedUsername);
			}
		} catch (Exception e) {
			log.warn("Credentials polling failed: {}", e.toString());
		}
	}

	private Properties loadFromStandardConfigLocations() {
		Properties props = new Properties();
		// Получаем имена из spring.config.name (по умолчанию 'application')
		String namesProp = environment.getProperty("spring.config.name", "application");
		List<String> names = Arrays.stream(namesProp.split(","))
			.map(String::trim)
			.filter(s -> !s.isEmpty())
			.toList();

		// Базовые локации: spring.config.location / spring.config.additional-location
		// + дефолты src/main/resources/, target/classes/, ./, ./config/
		List<String> locations = new ArrayList<>();
		addLocationsFromProperty(locations, environment.getProperty("spring.config.location", ""));
		addLocationsFromProperty(locations, environment.getProperty("spring.config.additional-location", ""));

		// Также попробуем извлечь реальные источники из имён PropertySource (applicationConfig: [file:..., classpath:...])
		for (Path p : extractResourceCandidatesFromPropertySources()) {
			log.info("PropertySource candidate: {} (exists: {})", p, Files.exists(p));
			loadPropertiesPath(props, p);
		}

		// Поищем на classpath через ContextClassLoader (application.{properties,yml,yaml})
		loadFromClasspathResources(props, names);

		// Если локации не заданы — используем текущую директорию как дефолт
		if (locations.isEmpty()) {
			locations.add("./");
		}

		log.info("Config search names: {}", names);
		log.info("Config search locations: {}", locations);

		// Ищем .properties, .yml, .yaml в указанных локациях
		for (String location : locations) {
			for (String name : names) {
				loadPropertiesCandidate(props, location, name + ".properties");
				loadPropertiesCandidate(props, location, name + ".yml");
				loadPropertiesCandidate(props, location, name + ".yaml");
			}
		}
		return props;
	}

	private void loadPropertiesCandidate(Properties target, String location, String filename) {
		try {
			String normalized = location;
			if (normalized.startsWith("file:")) {
				normalized = normalized.substring("file:".length());
			}
			Path path = Path.of(normalized).resolve(filename);
			log.info("Config candidate: {} (exists: {})", path, Files.exists(path));
			loadPropertiesPath(target, path);
		} catch (Exception e) {
			log.warn("Failed to load config from {}: {}", location, e.toString());
		}
	}

	private void loadPropertiesPath(Properties target, Path path) {
		try {
			if (!Files.exists(path)) {
				return;
			}
			if (path.toString().endsWith(".properties")) {
				Properties p = PropertiesLoaderUtils.loadProperties(new FileSystemResource(path));
				log.debug("Loaded properties from {}", path);
				target.putAll(p);
			} else if (path.toString().endsWith(".yml") || path.toString().endsWith(".yaml")) {
				YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
				yaml.setResources(new FileSystemResource(path));
				Properties p = yaml.getObject();
				if (p != null) {
					log.debug("Loaded YAML from {}", path);
					target.putAll(p);
				}
			}
		} catch (Exception e) {
			log.warn("Failed to load config from {}: {}", path, e.toString());
		}
	}

	private List<Path> extractResourceCandidatesFromPropertySources() {
		List<Path> result = new ArrayList<>();
		for (org.springframework.core.env.PropertySource<?> ps : environment.getPropertySources()) {
			String name = ps.getName();
			if (name == null) {
				continue;
			}
			int lb = name.indexOf('[');
			int rb = name.lastIndexOf(']');
			if (lb >= 0 && rb > lb) {
				String inside = name.substring(lb + 1, rb).trim();
				if (inside.startsWith("file:")) {
					String pathStr = inside.substring("file:".length());
					result.add(Path.of(pathStr));
					log.debug("Discovered config candidate from PropertySource: {}", pathStr);
				} else if (inside.startsWith("classpath:")) {
					String resPath = inside.substring("classpath:".length());
					if (resPath.startsWith("/")) resPath = resPath.substring(1);
					result.add(Path.of("./target/classes").resolve(resPath));
					result.add(Path.of("./src/main/resources").resolve(resPath));
					log.debug("Discovered classpath config candidates from PropertySource: target/classes/{}, src/main/resources/{}", resPath, resPath);
				}
			}
		}
		return result;
	}

	private void loadFromClasspathResources(Properties target, List<String> names) {
		ClassLoader cl = Thread.currentThread().getContextClassLoader();
		if (cl == null) {
			return;
		}
		for (String name : names) {
			for (String ext : List.of(".properties", ".yml", ".yaml")) {
				String resName = name + ext;
				try {
					URL url = cl.getResource(resName);
					log.info("Classpath candidate: {} -> {}", resName, url);
					if (url == null) {
						continue;
					}
					if (ext.equals(".properties")) {
						var res = new UrlResource(url);
						Properties p = PropertiesLoaderUtils.loadProperties(res);
						target.putAll(p);
						log.debug("Loaded properties from classpath URL {}", url);
					} else {
						var res = new UrlResource(url);
						YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
						yaml.setResources(res);
						Properties p = yaml.getObject();
						if (p != null) {
							target.putAll(p);
							log.debug("Loaded YAML from classpath URL {}", url);
						}
					}
				} catch (Exception e) {
					log.warn("Failed to load classpath resource {}: {}", resName, e.toString());
				}
			}
		}
	}

	private void addLocationsFromProperty(List<String> locations, String prop) {
		if (prop == null || prop.isBlank()) {
			return;
		}
		for (String raw : prop.split(",")) {
			String trimmed = raw.trim();
			if (!trimmed.isEmpty()) {
				locations.add(trimmed);
			}
		}
	}
}

