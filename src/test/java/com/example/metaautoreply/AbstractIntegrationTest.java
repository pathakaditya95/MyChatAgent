package com.example.metaautoreply;

import org.springframework.test.context.ActiveProfiles;

/**
 * Base for integration tests that need the full application against a real Postgres.
 *
 * <p>Extending this gives a test the shared container, the {@code test} profile, and every
 * {@code meta.*} and {@code admin.*} value pinned so the developer's environment cannot leak
 * in — see {@link IntegrationTest} for why that pinning is load-bearing rather than tidy.
 *
 * <p>Two classes deliberately do not extend it: {@code GraphApiRetryTest} and
 * {@code OutboundDispatcherTest} need {@code meta.dry-run=false} and a WireMock base URL, which
 * are exactly the values this base class exists to pin. They declare their own properties and
 * import {@link TestcontainersConfiguration} directly, so they still share the one container.
 */
@IntegrationTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {
}
