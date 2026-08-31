package com.example.metaautoreply;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 smoke test. Deliberately does not start the Spring context: at this
 * stage a context refresh would try to open a JDBC connection and fail without a
 * database. The full context test arrives in Phase 3 alongside
 * {@link TestcontainersConfiguration}.
 */
class ApplicationConfigTest {

	@Test
	void applicationClassIsAnnotated() {
		assertThat(MetaAutoReplyApplication.class.getAnnotation(SpringBootApplication.class))
				.as("entry point must be a Spring Boot application")
				.isNotNull();
	}

	@Test
	void applicationYamlParsesAndDeclaresRequiredKeys() throws Exception {
		List<PropertySource<?>> sources =
				new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));

		assertThat(sources).as("application.yml must be present and parseable").isNotEmpty();
		PropertySource<?> yaml = sources.get(0);

		// Every externalised setting the later phases depend on. Kept as an explicit
		// list so a typo in application.yml fails here rather than at runtime.
		assertThat(List.of(
				"spring.application.name",
				"spring.datasource.url",
				"spring.jpa.hibernate.ddl-auto",
				"spring.flyway.locations",
				"meta.app-secret",
				"meta.instagram-app-secret",
				"meta.verify-token",
				"meta.access-token",
				"meta.page-id",
				"meta.ig-user-id",
				"meta.graph-version",
				"meta.graph-base-url",
				"meta.dry-run",
				"autoreply.process-interval-ms",
				"autoreply.dispatch-interval-ms",
				"autoreply.max-send-attempts",
				"autoreply.sends-per-hour",
				"autoreply.jitter-ms"))
				.allSatisfy(key -> assertThat(yaml.getProperty(key)).as(key).isNotNull());
	}

	@Test
	void dryRunDefaultsToTrue() throws Exception {
		PropertySource<?> yaml = new YamlPropertySourceLoader()
				.load("application", new ClassPathResource("application.yml")).get(0);

		// Compliance guard: development must never send live traffic by accident.
		assertThat(yaml.getProperty("meta.dry-run")).isEqualTo("${META_DRY_RUN:true}");
	}
}
