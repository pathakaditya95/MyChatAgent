package com.example.metaautoreply;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Boots the full application against a Testcontainers Postgres, with every {@code meta.*}
 * value pinned.
 *
 * <p>The pinning is not redundant with {@code application-test.yml}. Spring Boot ranks OS
 * environment variables <em>above</em> profile-specific YAML packaged in the application, so a
 * developer who has sourced {@code .env} into their shell — or a VS Code test run configured
 * with {@code envFile} — silently overrides the test configuration with real credentials. That
 * produced a genuinely confusing failure: {@code meta.ig-user-id} bound to the real Instagram
 * account, the self-reply check stopped recognising the test account, and an event that should
 * have been ignored generated outbound messages.
 *
 * <p>Inline {@code properties} on {@code @SpringBootTest} rank above environment variables, so
 * they win. Two of these matter for safety rather than correctness:
 *
 * <ul>
 *   <li>{@code meta.dry-run=true} — an exported {@code META_DRY_RUN=false} must never let the
 *       suite send live traffic to a real Page or Instagram account.</li>
 *   <li>{@code autoreply.scheduling-enabled=false} — tests drive one batch at a time by hand;
 *       a timer firing mid-assertion is miserable to debug.</li>
 * </ul>
 *
 * <p>Profiles are left to the test class, since some need {@code debug} as well as {@code test}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@SpringBootTest(properties = {
		"meta.app-secret=test_app_secret",
		"meta.instagram-app-secret=test_instagram_app_secret",
		"meta.verify-token=test_verify_token",
		"meta.access-token=test_access_token",
		"meta.page-id=100000000000001",
		"meta.ig-user-id=200000000000002",
		"meta.graph-version=v21.0",
		"meta.graph-base-url=https://graph.facebook.com",
		"meta.dry-run=true",
		"admin.user=test_admin",
		"admin.password=test_admin_password",
		"autoreply.scheduling-enabled=false"
})
@Import(TestcontainersConfiguration.class)
public @interface IntegrationTest {
}
