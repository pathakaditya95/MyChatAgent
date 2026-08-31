package com.example.metaautoreply;

import com.example.metaautoreply.domain.KeywordRule;
import com.example.metaautoreply.domain.enums.EventStatus;
import com.example.metaautoreply.domain.enums.MatchType;
import com.example.metaautoreply.domain.enums.OutboundKind;
import com.example.metaautoreply.domain.enums.OutboundStatus;
import com.example.metaautoreply.domain.enums.Platform;
import com.example.metaautoreply.domain.enums.TriggerType;
import com.example.metaautoreply.delivery.OutboundDispatcher;
import com.example.metaautoreply.engine.EventProcessor;
import com.example.metaautoreply.repo.ContactRepository;
import com.example.metaautoreply.repo.InboundEventRepository;
import com.example.metaautoreply.repo.KeywordRuleRepository;
import com.example.metaautoreply.repo.OutboundMessageRepository;
import com.example.metaautoreply.security.SignatureVerifier;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole pipeline in one test: a signed HTTP delivery of a <em>real captured payload</em>
 * goes in, and the exact Graph API calls come out.
 *
 * <p>Every other test covers one layer. This one is the guard against the layers being
 * individually correct and collectively wrong — a field renamed between the normalizer and the
 * dispatcher, or the wrong account id reaching the messaging endpoint, would pass every unit
 * test and fail here.
 *
 * <p>Runs with {@code dry-run=false} against a WireMock on localhost, which is the only way to
 * assert on the requests that would actually be sent. Nothing can reach a real account.
 */
@SpringBootTest(properties = {
		"meta.app-secret=test_app_secret",
		"meta.instagram-app-secret=test_instagram_app_secret",
		"meta.verify-token=test_verify_token",
		"meta.access-token=test_access_token",
		"meta.page-id=824570447415713",
		"meta.ig-user-id=17841403243590103",
		"meta.graph-version=v21.0",
		"meta.dry-run=false",
		"admin.user=test_admin",
		"admin.password=test_admin_password",
		"autoreply.scheduling-enabled=false",
		"autoreply.jitter-ms=0"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class EndToEndFlowTest {

	private static final String IG_USER_ID = "17841403243590103";
	private static final String PAGE_ID = "824570447415713";

	private static WireMockServer wireMock;

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private SignatureVerifier signatureVerifier;
	@Autowired
	private EventProcessor processor;
	@Autowired
	private OutboundDispatcher dispatcher;
	@Autowired
	private InboundEventRepository events;
	@Autowired
	private OutboundMessageRepository outbound;
	@Autowired
	private KeywordRuleRepository rules;
	@Autowired
	private ContactRepository contacts;

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
		registry.add("meta.graph-base-url", () -> wireMock.baseUrl());
	}

	/**
	 * Cleans up before and after, and deliberately commits rather than rolling back.
	 *
	 * <p>This test cannot be {@code @Transactional}: wrapping the whole flow in one transaction
	 * means the webhook insert, the processor and the dispatcher all share it, and the
	 * {@code FOR UPDATE SKIP LOCKED} claims find nothing — the pipeline produces zero Graph API
	 * calls and the test passes vacuously or fails confusingly. Committing between stages is
	 * also what actually happens in production.
	 *
	 * <p>Since the suite now shares one Postgres, committing means cleaning up thoroughly.
	 * Only rules this class created are removed: a blanket {@code rules.deleteAll()} would take
	 * the V2 seed rows with it and fail {@code DomainPersistenceTest} in a different class.
	 */
	@BeforeEach
	void reset() {
		cleanUp();
		wireMock.resetAll();
	}

	@AfterEach
	void tearDown() {
		cleanUp();
	}

	private void cleanUp() {
		// outbound_message references inbound_event, so it has to go first.
		outbound.deleteAll();
		events.deleteAll();
		contacts.deleteAll();
		rules.findAll().stream()
				.filter(r -> r.getName().startsWith("e2e"))
				.forEach(rules::delete);
	}

	private static String fixture(String name) throws IOException {
		try (InputStream in = EndToEndFlowTest.class.getResourceAsStream("/fixtures/" + name)) {
			assertThat(in).as("fixture %s", name).isNotNull();
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/**
	 * Rewrites {@code value.created_time} to now.
	 *
	 * <p>The Facebook fixture is a real comment carrying its real capture timestamp, and
	 * compliance invariant #2 refuses a private reply to anything older than seven days. A test
	 * asserting that the private reply happens is therefore true when written and false a week
	 * later — which is exactly how it failed, with the product behaving correctly throughout.
	 * Tests that care about the reply path refresh the timestamp; the one that cares about the
	 * window deliberately does not.
	 */
	private static String withCurrentTimestamp(String fixtureJson) {
		return fixtureJson.replaceAll("\"created_time\"\\s*:\\s*\\d+",
				"\"created_time\": " + Instant.now().getEpochSecond());
	}

	/** Wraps a captured sub-payload back into the delivery envelope Meta actually posts. */
	private static String envelope(String object, String changesOrMessaging, String subPayload) {
		return """
				{"object":"%s","entry":[{"id":"1","time":1786796286,"%s":[%s]}]}"""
				.formatted(object, changesOrMessaging, subPayload);
	}

	private void deliver(String body) throws Exception {
		mockMvc.perform(post("/webhook")
						.contentType(MediaType.APPLICATION_JSON)
						.header("X-Hub-Signature-256", signatureVerifier.sign(body))
						.content(body))
				.andExpect(status().isOk());
	}

	private void stubEverythingOk() {
		wireMock.stubFor(WireMock.post(WireMock.urlMatching(".*"))
				.willReturn(aResponse().withStatus(200)
						.withHeader("Content-Type", "application/json")
						.withBody("{\"id\":\"reply_id\",\"message_id\":\"mid_id\"}")));
	}

	private void runBothSchedulers() {
		processor.processBatch();
		dispatcher.dispatchBatch();
	}

	@Test
	void aRealInstagramCommentProducesAPublicReplyAndAPrivateReply() throws Exception {
		stubEverythingOk();
		rules.save(new KeywordRule("e2e", Platform.IG, TriggerType.COMMENT, MatchType.CONTAINS,
				"test", "Thanks for commenting!", "Here are the details.", true, 1));

		deliver(envelope("instagram", "changes", fixture("ig-comment-1.json")));
		runBothSchedulers();

		// The comment id out of the real fixture, not one we invented.
		String commentId = "18002189276993311";

		wireMock.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/v21.0/" + commentId + "/replies"))
				.withHeader("Authorization", equalTo("Bearer test_access_token"))
				.withRequestBody(equalToJson("{\"message\":\"Thanks for commenting!\"}")));

		// The private reply is addressed by comment id and goes to OUR Instagram inbox.
		wireMock.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/v21.0/" + PAGE_ID + "/messages"))
				.withRequestBody(equalToJson("""
						{"recipient":{"comment_id":"%s"},"message":{"text":"Here are the details."}}"""
						.formatted(commentId))));

		assertThat(outbound.findAll())
				.hasSize(2)
				.allSatisfy(m -> assertThat(m.getStatus()).isEqualTo(OutboundStatus.SENT));
		assertThat(events.findAll()).singleElement()
				.satisfies(e -> assertThat(e.getStatus()).isEqualTo(EventStatus.DONE));
	}

	@Test
	void aRealFacebookCommentIsRoutedToTheFacebookEdgeAndPageInbox() throws Exception {
		stubEverythingOk();
		rules.save(new KeywordRule("e2e fb", Platform.FB, TriggerType.COMMENT, MatchType.CONTAINS,
				"grfg", "Thanks!", "Details.", true, 1));

		deliver(envelope("page", "changes", withCurrentTimestamp(fixture("fb-feed-1.json"))));
		runBothSchedulers();

		String commentId = "122141626023153354_1386416150303193";

		// Facebook public replies go to /comments, not /replies — the two platforms differ.
		wireMock.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/v21.0/" + commentId + "/comments"))
				.withRequestBody(equalToJson("{\"message\":\"Thanks!\"}")));
		wireMock.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/v21.0/" + PAGE_ID + "/messages")));
	}

	@Test
	void aRealInstagramMessageProducesADmToTheSender() throws Exception {
		stubEverythingOk();
		rules.save(new KeywordRule("e2e dm", Platform.IG, TriggerType.MESSAGE, MatchType.CONTAINS,
				"hi", null, "Hello back!", true, 1));

		deliver(envelope("instagram", "messaging", fixture("ig-message-3.json")));
		runBothSchedulers();

		wireMock.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/v21.0/" + PAGE_ID + "/messages"))
				.withRequestBody(equalToJson("""
						{"recipient":{"id":"694336747088628"},"message":{"text":"Hello back!"}}""")));

		assertThat(outbound.findAll()).singleElement()
				.satisfies(m -> assertThat(m.getKind()).isEqualTo(OutboundKind.DM));
	}

	/**
	 * Two of the captured Instagram message fixtures are echoes of our own outgoing DMs. If one
	 * ever reached the dispatcher the service would be talking to itself, so the end-to-end
	 * assertion is that Meta is never called at all.
	 */
	@Test
	void anEchoOfOurOwnMessageNeverReachesMeta() throws Exception {
		stubEverythingOk();
		rules.save(new KeywordRule("e2e echo", Platform.IG, TriggerType.MESSAGE, MatchType.CONTAINS,
				"", "x", "should never send", true, 1));

		deliver(envelope("instagram", "messaging", fixture("ig-message-1.json")));
		runBothSchedulers();

		assertThat(outbound.findAll()).isEmpty();
		assertThat(wireMock.findAll(WireMock.postRequestedFor(WireMock.urlMatching(".*")))).isEmpty();
	}

	/**
	 * Compliance invariant #2, end to end.
	 *
	 * <p>Uses the captured fixture with its original timestamp, unrefreshed. That comment was
	 * real and is now permanently more than seven days old, so it can never receive a private
	 * reply again — which makes this assertion stable rather than time-dependent. The public
	 * reply carries no such restriction and must still go out.
	 */
	@Test
	void aCommentOlderThanSevenDaysGetsAPublicReplyButNoPrivateReply() throws Exception {
		stubEverythingOk();
		rules.save(new KeywordRule("e2e window", Platform.FB, TriggerType.COMMENT,
				MatchType.CONTAINS, "grfg", "Thanks!", "Details.", true, 1));

		deliver(envelope("page", "changes", fixture("fb-feed-1.json")));
		runBothSchedulers();

		String commentId = "122141626023153354_1386416150303193";
		wireMock.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/v21.0/" + commentId + "/comments")));
		wireMock.verify(0, WireMock.postRequestedFor(
				WireMock.urlEqualTo("/v21.0/" + PAGE_ID + "/messages")));

		assertThat(outbound.findAll())
				.singleElement()
				.satisfies(m -> assertThat(m.getKind()).isEqualTo(OutboundKind.PUBLIC_REPLY));
	}

	/** A delivery Meta did not sign must never reach the pipeline at all. */
	@Test
	void anUnsignedDeliveryChangesNothing() throws Exception {
		stubEverythingOk();
		rules.save(new KeywordRule("e2e", Platform.IG, TriggerType.COMMENT, MatchType.CONTAINS,
				"test", "Thanks!", null, true, 1));

		mockMvc.perform(post("/webhook")
						.contentType(MediaType.APPLICATION_JSON)
						.header("X-Hub-Signature-256", "sha256=" + "0".repeat(64))
						.content(envelope("instagram", "changes", fixture("ig-comment-1.json"))))
				.andExpect(status().isForbidden());

		runBothSchedulers();

		assertThat(events.findAll()).isEmpty();
		assertThat(outbound.findAll()).isEmpty();
		assertThat(wireMock.findAll(WireMock.postRequestedFor(WireMock.urlMatching(".*")))).isEmpty();
	}
}
