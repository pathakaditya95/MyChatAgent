package com.example.metaautoreply;

import com.example.metaautoreply.config.MetaProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the test suite is insulated from the developer's environment.
 *
 * <p>Spring Boot ranks OS environment variables above profile YAML packaged in the app, so
 * sourcing {@code .env} into the shell — or running tests from VS Code with {@code envFile} —
 * silently replaces test configuration with real credentials. That already caused one
 * confusing failure: {@code meta.ig-user-id} bound to the real Instagram account, so the
 * self-reply check stopped recognising the test account and an event that should have been
 * ignored produced outbound messages.
 *
 * <p>{@link IntegrationTest} pins these values as inline {@code @SpringBootTest} properties,
 * which outrank environment variables. The {@code dry-run} assertion is the one that really
 * matters: an exported {@code META_DRY_RUN=false} must never let the suite send live traffic
 * to a real Page or Instagram account.
 */
class HermeticConfigTest extends AbstractIntegrationTest {

	@Autowired
	private MetaProperties props;

	@Test
	void dryRunIsForcedOnRegardlessOfTheEnvironment() {
		assertThat(props.dryRun())
				.as("tests must never be able to send live traffic")
				.isTrue();
	}

	@Test
	void accountIdsAreTheTestValuesNotWhateverIsInTheShell() {
		assertThat(props.pageId()).isEqualTo("100000000000001");
		assertThat(props.igUserId()).isEqualTo("200000000000002");
	}

	@Test
	void credentialsAreTheTestValues() {
		assertThat(props.appSecret()).isEqualTo("test_app_secret");
		assertThat(props.instagramAppSecret()).isEqualTo("test_instagram_app_secret");
		assertThat(props.verifyToken()).isEqualTo("test_verify_token");
		assertThat(props.accessToken()).isEqualTo("test_access_token");
		assertThat(props.graphBaseUrl())
				.as("no test may point at a host that could be real")
				.isEqualTo("https://graph.facebook.com");
	}
}
