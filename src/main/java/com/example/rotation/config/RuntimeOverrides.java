package com.example.rotation.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Component
public class RuntimeOverrides implements InitializingBean {

	private static final String PROPERTY_SOURCE_NAME = "runtimeOverrides";

	private final ConfigurableEnvironment environment;
	private Map<String, Object> backingMap = Collections.synchronizedMap(new HashMap<>());

	public RuntimeOverrides(ConfigurableEnvironment environment) {
		this.environment = environment;
	}

	@Override
	public void afterPropertiesSet() {
		MutablePropertySources sources = environment.getPropertySources();
		if (!sources.contains(PROPERTY_SOURCE_NAME)) {
			sources.addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, backingMap));
		}
	}

	public void putAll(Map<String, String> values) {
		values.forEach((k, v) -> backingMap.put(k, v));
	}

	public Map<String, Object> snapshot() {
		return new HashMap<>(backingMap);
	}
}

