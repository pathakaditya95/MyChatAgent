package com.example.metaautoreply.engine;

import com.example.metaautoreply.AbstractIntegrationTest;
import com.example.metaautoreply.domain.Contact;
import com.example.metaautoreply.domain.InboundEvent;
import com.example.metaautoreply.domain.KeywordRule;
import com.example.metaautoreply.domain.OutboundMessage;
import com.example.metaautoreply.domain.enums.EventStatus;
import com.example.metaautoreply.domain.enums.MatchType;
import com.example.metaautoreply.domain.enums.OutboundKind;
import com.example.metaautoreply.domain.enums.OutboundStatus;
import com.example.metaautoreply.domain.enums.Platform;
import com.example.metaautoreply.domain.enums.TriggerType;
import com.example.metaautoreply.repo.ContactRepository;
import com.example.metaautoreply.repo.InboundEventRepository;
import com.example.metaautoreply.repo.KeywordRuleRepository;
import com.example.metaautoreply.repo.OutboundMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end: rows in {@code inbound_event} become rows in {@code outbound_message}.
 *
 * <p>Schedulers are disabled under the {@code test} profile, so each test drives exactly one
 * batch by hand and nothing fires underneath an assertion.
 */
@Transactional
class EventProcessorTest extends AbstractIntegrationTest {

	private static final String PAGE_ID = "100000000000001";
	private static final String IG_USER_ID = "200000000000002";

	@Autowired
	private EventProcessor processor;
	@Autowired
	private InboundEventRepository events;
	@Autowired
	private OutboundMessageRepository outbound;
	@Autowired
	private KeywordRuleRepository rules;
	@Autowired
	private ContactRepository contacts;

	/**
	 * The class is {@code @Transactional}, so every test rolls back. That matters beyond
	 * tidiness: without it, clearing {@code keyword_rule} here would commit, and the V2 seed
	 * rules that {@link com.example.metaautoreply.domain.DomainPersistenceTest} asserts on
	 * would vanish for the rest of the run — a failure that appears in a different class
	 * entirely and depends on test ordering.
	 */
	@BeforeEach
	void clean() {
		outbound.deleteAll();
		events.deleteAll();
		contacts.deleteAll();
		rules.deleteAll();
	}

	private static String fixture(String name) throws IOException {
		try (InputStream in = EventProcessorTest.class.getResourceAsStream("/fixtures/" + name)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private KeywordRule enabledRule(Platform platform, TriggerType trigger, String keyword,
			String publicReply, String dmText) {
		return rules.save(new KeywordRule("test", platform, trigger, MatchType.CONTAINS,
				keyword, publicReply, dmText, true, 10));
	}

	private InboundEvent event(String key, Platform platform, String payload) {
		return events.save(new InboundEvent(key, platform, "COMMENT", payload));
	}

	private String igComment(String id, String senderId, String text) {
		return """
				{"field":"comments","value":{"id":"%s","from":{"id":"%s","username":"someone"},
				"text":"%s","media":{"id":"m1"}}}""".formatted(id, senderId, text);
	}

	// --- The main path ----------------------------------------------------------------

	@Test
	void aMatchingCommentQueuesBothAPublicReplyAndAPrivateReply() {
		enabledRule(Platform.IG, TriggerType.COMMENT, "price", "Just sent you a DM!", "Here are the details");
		event("c1", Platform.IG, igComment("c1", "user1", "what is the price?"));

		processor.processBatch();

		assertThat(outbound.findAll())
				.hasSize(2)
				.extracting(OutboundMessage::getKind, OutboundMessage::getTargetId, OutboundMessage::getStatus)
				.containsExactlyInAnyOrder(
						org.assertj.core.groups.Tuple.tuple(OutboundKind.PUBLIC_REPLY, "c1", OutboundStatus.PENDING),
						org.assertj.core.groups.Tuple.tuple(OutboundKind.PRIVATE_REPLY, "c1", OutboundStatus.PENDING));

		assertThat(events.findAll()).singleElement()
				.satisfies(e -> {
					assertThat(e.getStatus()).isEqualTo(EventStatus.DONE);
					assertThat(e.getProcessedAt()).isNotNull();
				});
	}

	@Test
	void worksAgainstARealCapturedInstagramFixture() throws IOException {
		enabledRule(Platform.IG, TriggerType.COMMENT, "test", "thanks!", null);
		event("18002189276993311", Platform.IG, fixture("ig-comment-1.json"));

		processor.processBatch();

		assertThat(outbound.findAll()).singleElement()
				.satisfies(m -> assertThat(m.getTargetId()).isEqualTo("18002189276993311"));
	}

	@Test
	void worksAgainstARealCapturedFacebookFixture() throws IOException {
		enabledRule(Platform.FB, TriggerType.COMMENT, "grfg", "thanks!", null);
		event("122141626023153354_1386416150303193", Platform.FB, fixture("fb-feed-1.json"));

		processor.processBatch();

		assertThat(outbound.findAll()).singleElement()
				.satisfies(m -> assertThat(m.getTargetId())
						.isEqualTo("122141626023153354_1386416150303193"));
	}

	// --- Compliance invariants --------------------------------------------------------

	/** Invariant #4. Without this the service answers its own replies forever. */
	@Test
	void anEventFromOurOwnAccountProducesNoOutboundRows() {
		enabledRule(Platform.IG, TriggerType.COMMENT, "price", "reply", "dm");
		event("c1", Platform.IG, igComment("c1", IG_USER_ID, "price?"));
		event("c2", Platform.FB, """
				{"field":"feed","value":{"item":"comment","verb":"add","from":{"id":"%s","name":"Us"},
				"message":"price?","post_id":"p1","comment_id":"c2"}}""".formatted(PAGE_ID));

		processor.processBatch();

		assertThat(outbound.findAll()).isEmpty();
		assertThat(events.findAll()).allSatisfy(e ->
				assertThat(e.getStatus()).isEqualTo(EventStatus.SKIPPED));
		assertThat(contacts.findAll()).as("we are not a contact").isEmpty();
	}

	/** Invariant #1, enforced in the database by unique (kind, target_id). */
	@Test
	void theSameCommentProcessedTwiceProducesExactlyOnePrivateReply() {
		enabledRule(Platform.IG, TriggerType.COMMENT, "price", null, "Here are the details");
		event("c1", Platform.IG, igComment("c1", "user1", "price?"));

		processor.processBatch();

		// A redelivery arrives as a fresh event row carrying the same comment id. The earlier
		// row is left in place — outbound_message references it by foreign key.
		event("c1-again", Platform.IG, igComment("c1", "user1", "price?"));
		processor.processBatch();

		assertThat(outbound.findAll())
				.filteredOn(m -> m.getKind() == OutboundKind.PRIVATE_REPLY)
				.hasSize(1);
	}

	/** Invariant #5. */
	@Test
	void aStopMessageOptsTheContactOutAndSuppressesTheReply() {
		enabledRule(Platform.IG, TriggerType.MESSAGE, "stop", null, "should not be sent");
		events.save(new InboundEvent("m1", Platform.IG, "MESSAGE", """
				{"sender":{"id":"user1"},"recipient":{"id":"%s"},
				"message":{"mid":"m1","text":"STOP"},"timestamp":1786796190078}""".formatted(IG_USER_ID)));

		processor.processBatch();

		assertThat(contacts.findByPlatformAndExternalId(Platform.IG, "user1").orElseThrow().isOptedOut())
				.isTrue();
		assertThat(outbound.findAll()).isEmpty();
	}

	@Test
	void optOutMatchesWholeWordsOnly() {
		enabledRule(Platform.IG, TriggerType.COMMENT, "price", "reply", null);
		event("c1", Platform.IG, igComment("c1", "user1", "I use a stopwatch for price checks"));

		processor.processBatch();

		assertThat(contacts.findByPlatformAndExternalId(Platform.IG, "user1").orElseThrow().isOptedOut())
				.as("'stopwatch' must not opt someone out")
				.isFalse();
		assertThat(outbound.findAll()).hasSize(1);
	}

	@Test
	void anAlreadyOptedOutContactGetsNothing() {
		Contact optedOut = new Contact(Platform.IG, "user1", "someone");
		optedOut.setOptedOut(true);
		contacts.save(optedOut);
		enabledRule(Platform.IG, TriggerType.COMMENT, "price", "reply", "dm");
		event("c1", Platform.IG, igComment("c1", "user1", "price?"));

		processor.processBatch();

		assertThat(outbound.findAll()).isEmpty();
	}

	/** Invariant #2: a comment older than 7 days may not receive a private reply. */
	@Test
	void anOldCommentGetsAPublicReplyButNoPrivateReply() {
		enabledRule(Platform.FB, TriggerType.COMMENT, "price", "public is fine", "dm is not");
		long eightDaysAgo = Instant.now().minus(8, ChronoUnit.DAYS).getEpochSecond();
		event("c1", Platform.FB, """
				{"field":"feed","value":{"item":"comment","verb":"add","from":{"id":"u1","name":"X"},
				"message":"price?","post_id":"p1","comment_id":"c1","created_time":%d}}"""
				.formatted(eightDaysAgo));

		processor.processBatch();

		assertThat(outbound.findAll())
				.extracting(OutboundMessage::getKind)
				.containsExactly(OutboundKind.PUBLIC_REPLY);
	}

	// --- Bookkeeping ------------------------------------------------------------------

	@Test
	void anEventWithNoMatchingRuleIsSkipped() {
		enabledRule(Platform.IG, TriggerType.COMMENT, "price", "reply", null);
		event("c1", Platform.IG, igComment("c1", "user1", "nice photo"));

		processor.processBatch();

		assertThat(outbound.findAll()).isEmpty();
		assertThat(events.findAll()).singleElement()
				.satisfies(e -> assertThat(e.getStatus()).isEqualTo(EventStatus.SKIPPED));
	}

	@Test
	void aDisabledRuleNeverFires() {
		rules.save(new KeywordRule("off", Platform.IG, TriggerType.COMMENT, MatchType.CONTAINS,
				"price", "reply", "dm", false, 10));
		event("c1", Platform.IG, igComment("c1", "user1", "price?"));

		processor.processBatch();

		assertThat(outbound.findAll()).isEmpty();
	}

	@Test
	void contactIsCreatedAndLastInteractionStamped() {
		enabledRule(Platform.IG, TriggerType.COMMENT, "price", "reply", null);
		event("c1", Platform.IG, igComment("c1", "user1", "price?"));

		processor.processBatch();

		Contact contact = contacts.findByPlatformAndExternalId(Platform.IG, "user1").orElseThrow();
		assertThat(contact.getUsername()).isEqualTo("someone");
		assertThat(contact.getLastInteractionAt()).isNotNull();
	}

	@Test
	void anEmptyBatchIsHarmless() {
		processor.processBatch();
		assertThat(outbound.findAll()).isEmpty();
	}

	@Test
	void aMessageRuleQueuesADmTargetedAtTheSender() {
		enabledRule(Platform.IG, TriggerType.MESSAGE, "hello", null, "hi back");
		events.save(new InboundEvent("m1", Platform.IG, "MESSAGE", """
				{"sender":{"id":"user1"},"recipient":{"id":"%s"},
				"message":{"mid":"m1","text":"hello there"},"timestamp":1786796190078}"""
				.formatted(IG_USER_ID)));

		processor.processBatch();

		assertThat(outbound.findAll()).singleElement().satisfies(m -> {
			assertThat(m.getKind()).isEqualTo(OutboundKind.DM);
			assertThat(m.getTargetId()).as("a DM goes to the person, not the message").isEqualTo("user1");
		});
	}

	@Test
	void allEventsInABatchAreProcessed() {
		enabledRule(Platform.IG, TriggerType.COMMENT, "price", "reply", null);
		List.of("c1", "c2", "c3").forEach(id ->
				event(id, Platform.IG, igComment(id, "user-" + id, "price?")));

		processor.processBatch();

		assertThat(outbound.findAll()).hasSize(3);
		assertThat(events.findAll()).allSatisfy(e ->
				assertThat(e.getStatus()).isEqualTo(EventStatus.DONE));
	}
}
