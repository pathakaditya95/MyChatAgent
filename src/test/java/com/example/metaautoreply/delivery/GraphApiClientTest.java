package com.example.metaautoreply.delivery;

import com.example.metaautoreply.config.MetaProperties;
import com.example.metaautoreply.config.RestClientConfig;
import com.example.metaautoreply.delivery.exceptions.FatalMetaException;
import com.example.metaautoreply.delivery.exceptions.ReauthMetaException;
import com.example.metaautoreply.delivery.exceptions.RetryableMetaException;
import com.example.metaautoreply.domain.enums.Platform;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the four Graph API calls against WireMock — no Spring context, so this is fast and
 * the assertions are about the wire format only.
 *
 * <p>Retry behaviour is not tested here: {@code @Retry} is an AOP concern and needs a context.
 * See {@link GraphApiRetryTest}.
 */
class GraphApiClientTest {

	private static final String VERSION = "v21.0";
	private static final String PAGE_ID = "100000000000001";
	private static final String IG_USER_ID = "200000000000002";
	private static final String TOKEN = "test_access_token";

	private WireMockServer wireMock;
	private GraphApiClient client;

	private MetaProperties props(String baseUrl, boolean dryRun) {
		return new MetaProperties("secret", null, "verify", TOKEN, PAGE_ID, IG_USER_ID,
				VERSION, baseUrl, dryRun);
	}

	/**
	 * Builds the client through the real {@link RestClientConfig} rather than a bare
	 * {@code RestClient.builder()}. That keeps the timeouts, request factory and logging
	 * interceptor under test, and avoids the default factory negotiating HTTP/2 against
	 * WireMock, which fails with {@code RST_STREAM: Stream cancelled}.
	 */
	private GraphApiClient clientFor(MetaProperties props) {
		JsonMapper mapper = JsonMapper.builder().build();
		RestClient restClient = new RestClientConfig().graphRestClient(props);
		return new GraphApiClient(restClient, props, new MetaErrorClassifier(mapper), mapper);
	}

	@BeforeEach
	void startServer() {
		wireMock = new WireMockServer(options().dynamicPort());
		wireMock.start();
		client = clientFor(props(wireMock.baseUrl(), false));
	}

	@AfterEach
	void stopServer() {
		wireMock.stop();
	}

	private void stubOk(String url, String body) {
		wireMock.stubFor(post(urlEqualTo(url))
				.willReturn(aResponse().withStatus(200)
						.withHeader("Content-Type", "application/json")
						.withBody(body)));
	}

	private void stubError(String url, int status, String body) {
		wireMock.stubFor(post(urlEqualTo(url))
				.willReturn(aResponse().withStatus(status)
						.withHeader("Content-Type", "application/json")
						.withBody(body)));
	}

	// --- The four calls ---------------------------------------------------------------

	@Test
	void replyToIgCommentPostsToTheRepliesEdge() {
		String url = "/" + VERSION + "/comment_1/replies";
		stubOk(url, "{\"id\":\"reply_1\"}");

		assertThat(client.replyToIgComment("comment_1", "thanks!")).isEqualTo("reply_1");

		wireMock.verify(postRequestedFor(urlEqualTo(url))
				.withHeader("Authorization", equalTo("Bearer " + TOKEN))
				.withRequestBody(equalToJson("{\"message\":\"thanks!\"}")));
	}

	@Test
	void replyToFbCommentPostsToTheCommentsEdge() {
		String url = "/" + VERSION + "/comment_2/comments";
		stubOk(url, "{\"id\":\"reply_2\"}");

		assertThat(client.replyToFbComment("comment_2", "hello")).isEqualTo("reply_2");

		wireMock.verify(postRequestedFor(urlEqualTo(url))
				.withHeader("Authorization", equalTo("Bearer " + TOKEN))
				.withRequestBody(equalToJson("{\"message\":\"hello\"}")));
	}

	@Test
	void privateReplyAddressesTheCommentAndGoesToThePageInbox() {
		String url = "/" + VERSION + "/" + PAGE_ID + "/messages";
		stubOk(url, "{\"recipient_id\":\"u1\",\"message_id\":\"mid_1\"}");

		assertThat(client.privateReply(Platform.IG, "comment_3", "details")).isEqualTo("mid_1");

		wireMock.verify(postRequestedFor(urlEqualTo(url))
				.withHeader("Authorization", equalTo("Bearer " + TOKEN))
				.withRequestBody(equalToJson("""
						{"recipient":{"comment_id":"comment_3"},"message":{"text":"details"}}""")));
	}

	@Test
	void privateReplyOnFacebookGoesToThePageInbox() {
		String url = "/" + VERSION + "/" + PAGE_ID + "/messages";
		stubOk(url, "{\"message_id\":\"mid_2\"}");

		assertThat(client.privateReply(Platform.FB, "comment_4", "details")).isEqualTo("mid_2");

		wireMock.verify(postRequestedFor(urlEqualTo(url)));
	}

	@Test
	void sendDmAddressesTheRecipientById() {
		String url = "/" + VERSION + "/" + PAGE_ID + "/messages";
		stubOk(url, "{\"message_id\":\"mid_3\"}");

		assertThat(client.sendDm(Platform.IG, "user_9", "hi")).isEqualTo("mid_3");

		wireMock.verify(postRequestedFor(urlEqualTo(url))
				.withRequestBody(equalToJson("""
						{"recipient":{"id":"user_9"},"message":{"text":"hi"}}""")));
	}

	/** A never-expiring token in a query string would leak into access and proxy logs. */
	@Test
	void theTokenIsNeverPutInTheQueryString() {
		String url = "/" + VERSION + "/comment_5/replies";
		stubOk(url, "{\"id\":\"r\"}");

		client.replyToIgComment("comment_5", "x");

		wireMock.verify(postRequestedFor(urlEqualTo(url))
				.withQueryParam("access_token", absent()));
	}

	// --- Error classification ----------------------------------------------------------

	@Test
	void anExpiredTokenRaisesReauth() {
		String url = "/" + VERSION + "/comment_6/replies";
		stubError(url, 400, """
				{"error":{"message":"Error validating access token","code":190,"error_subcode":463}}""");

		assertThatThrownBy(() -> client.replyToIgComment("comment_6", "x"))
				.isInstanceOf(ReauthMetaException.class)
				.hasMessageContaining("code 190")
				.hasMessageContaining("subcode 463");
	}

	@Test
	void aRateLimitCodeIsRetryable() {
		String url = "/" + VERSION + "/comment_7/replies";
		stubError(url, 400, """
				{"error":{"message":"Application request limit reached","code":4}}""");

		assertThatThrownBy(() -> client.replyToIgComment("comment_7", "x"))
				.isInstanceOf(RetryableMetaException.class);
	}

	@Test
	void aServerErrorIsRetryableRegardlessOfBody() {
		String url = "/" + VERSION + "/comment_8/replies";
		stubError(url, 503, "gateway is unhappy");

		assertThatThrownBy(() -> client.replyToIgComment("comment_8", "x"))
				.isInstanceOf(RetryableMetaException.class);
	}

	@Test
	void aPermissionErrorIsFatal() {
		String url = "/" + VERSION + "/comment_9/replies";
		stubError(url, 400, """
				{"error":{"message":"Permissions error","code":200}}""");

		assertThatThrownBy(() -> client.replyToIgComment("comment_9", "x"))
				.isInstanceOf(FatalMetaException.class);
	}

	/**
	 * An unknown code must not be retried. Hammering Meta with an error we do not understand
	 * risks tripping anti-spam, which costs the account rather than one message.
	 */
	@Test
	void anUnrecognisedCodeIsTreatedAsFatal() {
		String url = "/" + VERSION + "/comment_10/replies";
		stubError(url, 400, """
				{"error":{"message":"Something brand new","code":99999}}""");

		assertThatThrownBy(() -> client.replyToIgComment("comment_10", "x"))
				.isInstanceOf(FatalMetaException.class)
				.hasMessageContaining("99999");
	}

	@Test
	void aSuccessResponseMissingTheIdStillCountsAsSent() {
		String url = "/" + VERSION + "/comment_11/replies";
		stubOk(url, "{}");

		// Meta accepted it; losing the id costs traceability, not delivery.
		assertThat(client.replyToIgComment("comment_11", "x")).isEqualTo("unknown");
	}

	// --- Dry run -----------------------------------------------------------------------

	@Test
	void dryRunMakesNoHttpCallsAtAll() {
		GraphApiClient dryRunClient = clientFor(props(wireMock.baseUrl(), true));

		assertThat(dryRunClient.replyToIgComment("c", "x")).startsWith("dry-run-");
		assertThat(dryRunClient.replyToFbComment("c", "x")).startsWith("dry-run-");
		assertThat(dryRunClient.privateReply(Platform.IG, "c", "x")).startsWith("dry-run-");
		assertThat(dryRunClient.sendDm(Platform.FB, "u", "x")).startsWith("dry-run-");

		assertThat(wireMock.findAll(WireMock.anyRequestedFor(WireMock.anyUrl())))
				.as("dry run must not touch the network")
				.isEmpty();
	}
}
