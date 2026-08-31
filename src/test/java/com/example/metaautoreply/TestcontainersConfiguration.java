package com.example.metaautoreply;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The one Postgres container the whole suite shares.
 *
 * <p>Spring caches a context per distinct configuration, and this project legitimately has
 * several — the debug profile, the two classes that need {@code dry-run=false} against
 * WireMock. A per-context container meant starting Postgres five times per build. Holding it in
 * a static field started once amortises that to a single start.
 *
 * <p>Two details make the sharing safe:
 *
 * <ul>
 *   <li>The container is started in a static initialiser rather than by Spring, so the first
 *       context to load brings it up and the rest attach to it.</li>
 *   <li>{@code destroyMethod = ""} stops Spring calling {@code close()} on it when a context
 *       shuts down. Without that, the first context to be evicted would stop the container out
 *       from under every other context still using it.</li>
 * </ul>
 *
 * <p>Nothing stops it at the end of the run: Testcontainers' Ryuk sidecar reaps it when the JVM
 * exits.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	private static final PostgreSQLContainer SHARED_POSTGRES =
			new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

	static {
		SHARED_POSTGRES.start();
	}

	@Bean(destroyMethod = "")
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return SHARED_POSTGRES;
	}

	/** Exposed so a test can assert there is only ever one instance. */
	public static PostgreSQLContainer sharedPostgres() {
		return SHARED_POSTGRES;
	}
}
