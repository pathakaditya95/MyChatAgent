package com.example.metaautoreply.delivery;

import com.example.metaautoreply.TestcontainersConfiguration;
import com.example.metaautoreply.delivery.exceptions.ReauthMetaException;
import com.example.metaautoreply.delivery.exceptions.RetryableMetaException;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Retry and circuit-breaker behaviour, which are AOP concerns and therefore need a real context.
 *
 * <p>This class cannot use {@code @IntegrationTest}: that pins {@code meta.dry-run=true} and a
 * real {@code graph-base-url}, both of which would defeat the point. {@code @DynamicPropertySource}
 * outranks inline properties, and the base URL points at a local WireMock, so no request can
 * reach anything real.
 */
@SpringBootTest(properties = {
		"meta.app-secret=test_app_secret",
		"meta.verify-token=test_verify_token",
		"meta.access-token=test_access_token",
		"meta.page-id=100000000000001",
		"meta.ig-user-id=200000000000002",
		"meta.graph-version=v21.0",
		"autoreply.scheduling-enabled=false",
		"admin.user=test_admin",
		"admin.password=test_admin_password",
		// Deliberately off: this class exists to exercise real HTTP against WireMock.
		"meta.dry-run=false"
})
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class GraphApiRetryTest {

	private static final String URL = "/v21.0/comment_1/replies";

	private static WireMockServer wireMock;

	@Autowired
	private GraphApiClient client;

	@BeforeAll
	static void startServer() {
		wireMock = new WireMockServer(options().dynamicPort());
		wireMock.start();
	}

	@AfterAll
	static void stopServer() {
		wireMock.stop();
	}

	@DynamicPropertySource
	static void graphBaseUrl(DynamicPropertyRegistry registry) {
		// Localhost only — nothing here can reach a real account.
		registry.add("meta.graph-base-url", () -> wireMock.baseUrl());
	}

	@BeforeEach
	void resetStubs() {
		wireMock.resetAll();
	}

	/** 3 attempts total, per the Resilience4j config, then the failure surfaces. */
	@Test
	void aServerErrorIsRetriedThreeTimesThenThrows() {
		wireMock.stubFor(post(urlEqualTo(URL)).willReturn(aResponse().withStatus(500)));

		assertThatThrownBy(() -> client.replyToIgComment("comment_1", "x"))
				.isInstanceOf(RetryableMetaException.class);

		wireMock.verify(3, postRequestedFor(urlEqualTo(URL)));
	}

	/**
	 * The important negative case. A dead token fails identically every time, so retrying only
	 * burns rate limit and delays the ERROR that tells a human to regenerate it.
	 */
	@Test
	void anExpiredTokenIsNotRetried() {
		wireMock.stubFor(post(urlEqualTo(URL)).willReturn(aResponse().withStatus(400)
				.withHeader("Content-Type", "application/json")
				.withBody("{\"error\":{\"message\":\"expired\",\"code\":190}}")));

		assertThatThrownBy(() -> client.replyToIgComment("comment_1", "x"))
				.isInstanceOf(ReauthMetaException.class);

		wireMock.verify(1, postRequestedFor(urlEqualTo(URL)));
	}

	@Test
	void aFatalErrorIsNotRetried() {
		wireMock.stubFor(post(urlEqualTo(URL)).willReturn(aResponse().withStatus(400)
				.withHeader("Content-Type", "application/json")
				.withBody("{\"error\":{\"message\":\"permissions\",\"code\":200}}")));

		assertThatThrownBy(() -> client.replyToIgComment("comment_1", "x"));

		wireMock.verify(1, postRequestedFor(urlEqualTo(URL)));
	}

	@Test
	void aTransientFailureThatRecoversSucceedsWithoutSurfacingAnError() {
		wireMock.stubFor(post(urlEqualTo(URL))
				.inScenario("recovers").whenScenarioStateIs("Started")
				.willReturn(aResponse().withStatus(500))
				.willSetStateTo("second"));
		wireMock.stubFor(post(urlEqualTo(URL))
				.inScenario("recovers").whenScenarioStateIs("second")
				.willReturn(aResponse().withStatus(200)
						.withHeader("Content-Type", "application/json")
						.withBody("{\"id\":\"reply_after_retry\"}")));

		org.assertj.core.api.Assertions.assertThat(client.replyToIgComment("comment_1", "x"))
				.isEqualTo("reply_after_retry");

		wireMock.verify(2, postRequestedFor(urlEqualTo(URL)));
	}
}
