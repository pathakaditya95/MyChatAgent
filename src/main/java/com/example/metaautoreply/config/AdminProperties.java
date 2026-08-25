package com.example.metaautoreply.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Credentials for the admin API's HTTP Basic login.
 *
 * <p>Same fail-fast guard as {@link MetaProperties}, and for the same reason: an unresolved
 * {@code ${ADMIN_PASSWORD}} placeholder binds as literal text rather than throwing, which would
 * leave the rule API protected by a password published in this repository.
 */
@ConfigurationProperties(prefix = "admin")
public record AdminProperties(String user, String password) {

	public AdminProperties {
		requireResolved("ADMIN_USER", "admin.user", user);
		requireResolved("ADMIN_PASSWORD", "admin.password", password);
	}

	private static void requireResolved(String envVar, String property, String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(
					"Missing required configuration '%s'. Set the %s environment variable."
							.formatted(property, envVar));
		}
		if (value.startsWith("${") && value.endsWith("}")) {
			throw new IllegalStateException(
					("Configuration '%s' resolved to the literal placeholder %s, which means %s is "
							+ "not set. Refusing to start rather than expose the admin API behind a "
							+ "publicly known password.").formatted(property, value, envVar));
		}
	}
}
