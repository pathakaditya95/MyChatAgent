package com.example.metaautoreply.delivery;

import com.example.metaautoreply.TestcontainersConfiguration;
import com.example.metaautoreply.delivery.exceptions.FatalMetaException;
import com.example.metaautoreply.delivery.exceptions.ReauthMetaException;
import com.example.metaautoreply.delivery.exceptions.RetryableMetaException;
import com.example.metaautoreply.domain.OutboundMessage;
import com.example.metaautoreply.domain.enums.OutboundKind;
import com.example.metaautoreply.domain.enums.OutboundStatus;
import com.example.metaautoreply.domain.enums.Platform;
import com.example.metaautoreply.repo.OutboundMessageRepository;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/**
 * Dispatcher behaviour end to end, against a real database.
 *
 * <p>Real HTTP is used for the happy path (WireMock on localhost, so nothing can reach a real
 * account); failure modes are driven by mocking {@link GraphApiClient}, which is the only
 * practical way to produce a specific classified exception on demand.
 */
@SpringBootTest(properties = {
		"meta.app-secret=test_app_secret",
		"meta.verify-token=test_verify_token",
		"meta.access-token=test_access_token",
		"meta.page-id=100000000000001",
		"meta.ig-user-id=200000000000002",
		"meta.graph-version=v21.0",
		"meta.dry-run=false",
		"autoreply.scheduling-enabled=false",
		"admin.user=test_admin",
		"admin.password=test_admin_password",
		// No jitter: these tests assert behaviour, not pacing, and 1.5s per send would make
		// the class needlessly slow.
		"autoreply.jitter-ms=0",
		"autoreply.max-send-attempts=5"
})
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class OutboundDispatcherTest {

	private static WireMockServer wireMock;

	@Autowired
	private OutboundDispatcher dispatcher;
	@Autowired
	private OutboundMessageRepository outbound;

	@MockitoBean
	private GraphApiClient graphApi;

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

	@BeforeEach
	void clean() {
		outbound.deleteAll();
		wireMock.resetAll();
		wireMock.stubFor(post(urlMatching(".*")).willReturn(aResponse().withStatus(200)
				.withHeader("Content-Type", "application/json")
				.withBody("{\"id\":\"x\",\"message_id\":\"x\"}")));
	}

	/**
	 * Queues a message that is unambiguously due.
	 *
	 * <p>{@code next_attempt_at} defaults to {@code Instant.now()} stamped by the JVM, while
	 * {@code lockBatch} compares it against SQL {@code now()} from the database container.
	 * Those two clocks are close but not identical, so a row created "now" sits on the boundary
	 * of being claimable. Backdating removes the coin-flip: these tests are about dispatch
	 * behaviour, not about which clock wins.
	 */
	private OutboundMessage queued(OutboundKind kind, Platform platform, String target) {
		OutboundMessage message = new OutboundMessage(kind, platform, target, "body", null);
		message.setNextAttemptAt(Instant.now().minusSeconds(30));
		return outbound.save(message);
	}

	private OutboundMessage reload(OutboundMessage message) {
		return outbound.findById(message.getId()).orElseThrow();
	}

	// --- Happy path ---------------------------------------------------------------------

	@Test
	void threePendingMessagesAllBecomeSent() {
		given(graphApi.replyToIgComment(anyString(), anyString())).willReturn("provider_1");
		queued(OutboundKind.PUBLIC_REPLY, Platform.IG, "c1");
		queued(OutboundKind.PUBLIC_REPLY, Platform.IG, "c2");
		queued(OutboundKind.PUBLIC_REPLY, Platform.IG, "c3");

		dispatcher.dispatchBatch();

		assertThat(outbound.findAll()).allSatisfy(m -> {
			assertThat(m.getStatus()).isEqualTo(OutboundStatus.SENT);
			assertThat(m.getSentAt()).isNotNull();
			assertThat(m.getProviderMsgId()).isEqualTo("provider_1");
		});
	}

	@Test
	void eachKindIsRoutedToTheRightCall() {
		given(graphApi.replyToIgComment(anyString(), anyString())).willReturn("ig_public");
		given(graphApi.replyToFbComment(anyString(), anyString())).willReturn("fb_public");
		given(graphApi.privateReply(any(), anyString(), anyString())).willReturn("private");
		given(graphApi.sendDm(any(), anyString(), anyString())).willReturn("dm");

		queued(OutboundKind.PUBLIC_REPLY, Platform.IG, "c1");
		queued(OutboundKind.PUBLIC_REPLY, Platform.FB, "c2");
		queued(OutboundKind.PRIVATE_REPLY, Platform.IG, "c3");
		queued(OutboundKind.DM, Platform.FB, "u4");

		dispatcher.dispatchBatch();

		then(graphApi).should().replyToIgComment("c1", "body");
		then(graphApi).should().replyToFbComment("c2", "body");
		then(graphApi).should().privateReply(Platform.IG, "c3", "body");
		then(graphApi).should().sendDm(Platform.FB, "u4", "body");
	}

	/**
	 * When a comment has both, the visible reply must land before the DM so the commenter sees
	 * the acknowledgement first. Note this cannot be achieved with a plain {@code order by
	 * kind}: as text, PRIVATE_REPLY sorts before PUBLIC_REPLY.
	 */
	@Test
	void publicRepliesAreSentBeforePrivateRepliesForTheSameComment() {
		given(graphApi.replyToIgComment(anyString(), anyString())).willReturn("pub");
		given(graphApi.privateReply(any(), anyString(), anyString())).willReturn("priv");

		queued(OutboundKind.PRIVATE_REPLY, Platform.IG, "c1");
		queued(OutboundKind.PUBLIC_REPLY, Platform.IG, "c1");

		assertThat(outbound.lockBatch(10))
				.extracting(OutboundMessage::getKind)
				.containsExactly(OutboundKind.PUBLIC_REPLY, OutboundKind.PRIVATE_REPLY);
	}

	// --- Failure handling ----------------------------------------------------------------

	@Test
	void aRetryableFailureGoesBackToPendingWithBackoffAndOneAttempt() {
		given(graphApi.replyToIgComment(anyString(), anyString()))
				.willThrow(new RetryableMetaException("boom", 500, -1, -1, "{}"));
		OutboundMessage message = queued(OutboundKind.PUBLIC_REPLY, Platform.IG, "c1");

		dispatcher.dispatchBatch();

		OutboundMessage after = reload(message);
		assertThat(after.getStatus()).isEqualTo(OutboundStatus.PENDING);
		assertThat(after.getAttempts()).isEqualTo(1);
		assertThat(after.getNextAttemptAt()).isAfter(Instant.now());
		assertThat(after.getLastError()).contains("boom");
	}

	@Test
	void backoffGrowsExponentially() {
		given(graphApi.replyToIgComment(anyString(), anyString()))
				.willThrow(new RetryableMetaException("boom", 500, -1, -1, "{}"));
		OutboundMessage message = queued(OutboundKind.PUBLIC_REPLY, Platform.IG, "c1");
		message.setAttempts(2);
		outbound.save(message);

		dispatcher.dispatchBatch();

		// attempts becomes 3 -> 2^3 = 8 minutes
		assertThat(reload(message).getNextAttemptAt())
				.isAfter(Instant.now().plusSeconds(7 * 60))
				.isBefore(Instant.now().plusSeconds(9 * 60));
	}

	@Test
	void oneMoreFailureAtTheAttemptCeilingAbandonsTheMessage() {
		given(graphApi.replyToIgComment(anyString(), anyString()))
				.willThrow(new RetryableMetaException("boom", 500, -1, -1, "{}"));
		OutboundMessage message = queued(OutboundKind.PUBLIC_REPLY, Platform.IG, "c1");
		message.setAttempts(4);
		outbound.save(message);

		dispatcher.dispatchBatch();

		assertThat(reload(message).getStatus()).isEqualTo(OutboundStatus.ABANDONED);
	}

	@Test
	void aFatalErrorFailsImmediatelyWithoutRetry() {
		given(graphApi.replyToIgComment(anyString(), anyString()))
				.willThrow(new FatalMetaException("permissions", 400, 200, -1, "{}"));
		OutboundMessage message = queued(OutboundKind.PUBLIC_REPLY, Platform.IG, "c1");

		dispatcher.dispatchBatch();

		OutboundMessage after = reload(message);
		assertThat(after.getStatus()).isEqualTo(OutboundStatus.FAILED);
		assertThat(after.getAttempts()).as("no retry, so no attempt counted").isZero();
		assertThat(after.getLastError()).contains("permissions");
	}

	@Test
	void aDeadTokenFailsTheMessage() {
		given(graphApi.replyToIgComment(anyString(), anyString()))
				.willThrow(new ReauthMetaException("expired", 400, 190, -1, "{}"));
		OutboundMessage message = queued(OutboundKind.PUBLIC_REPLY, Platform.IG, "c1");

		dispatcher.dispatchBatch();

		assertThat(reload(message).getStatus()).isEqualTo(OutboundStatus.FAILED);
	}

	// --- Rate limiting -------------------------------------------------------------------

	@Test
	void nothingIsSentOnceTheBucketIsExhausted() {
		given(graphApi.replyToIgComment(anyString(), anyString())).willReturn("ok");
		// sends-per-hour is 200 in the test profile; drain it.
		SendRateLimiter limiter = new SendRateLimiter(
				new com.example.metaautoreply.config.AppProperties(2000, 1000, 5, 1, 0));
		limiter.tryConsume(Platform.IG);

		OutboundDispatcher limited = new OutboundDispatcher(outbound, graphApi, limiter,
				new com.example.metaautoreply.config.AppProperties(2000, 1000, 5, 1, 0));
		OutboundMessage message = queued(OutboundKind.PUBLIC_REPLY, Platform.IG, "c1");

		limited.dispatchBatch();

		then(graphApi).should(never()).replyToIgComment(anyString(), anyString());
		OutboundMessage after = reload(message);
		assertThat(after.getStatus()).as("still queued, not failed").isEqualTo(OutboundStatus.PENDING);
		assertThat(after.getAttempts()).as("a rate limit is not the message's fault").isZero();
		assertThat(after.getNextAttemptAt()).isAfter(Instant.now().plusSeconds(30));
	}

	// --- Concurrency ---------------------------------------------------------------------

	/**
	 * The SKIP LOCKED proof. Two transactions claiming batches at the same time must never
	 * receive the same row — that is the guarantee which stops a message being sent twice when
	 * more than one instance runs.
	 */
	@Test
	void concurrentDispatchersNeverClaimTheSameRow() throws Exception {
		for (int i = 0; i < 20; i++) {
			queued(OutboundKind.PUBLIC_REPLY, Platform.IG, "concurrent_" + i);
		}

		List<Long> first = List.of();
		List<Long> second = List.of();
		try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
			var latch = new java.util.concurrent.CountDownLatch(1);
			var taskA = executor.submit(() -> claimIds(latch));
			var taskB = executor.submit(() -> claimIds(latch));
			latch.countDown();
			first = taskA.get();
			second = taskB.get();
		}

		assertThat(first).isNotEmpty();
		assertThat(second).isNotEmpty();
		assertThat(first).as("no row claimed by both").doesNotContainAnyElementsOf(second);
	}

	@Autowired
	private org.springframework.transaction.PlatformTransactionManager txManager;

	private List<Long> claimIds(java.util.concurrent.CountDownLatch latch) {
		try {
			latch.await();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		var template = new org.springframework.transaction.support.TransactionTemplate(txManager);
		return template.execute(status -> {
			List<Long> ids = outbound.lockBatch(10).stream().map(OutboundMessage::getId).toList();
			// Hold the locks briefly so the other transaction is genuinely concurrent.
			try {
				Thread.sleep(300);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return ids;
		});
	}

	@Test
	void anEmptyQueueIsHarmless() {
		dispatcher.dispatchBatch();
		assertThat(outbound.findAll()).isEmpty();
	}
}
