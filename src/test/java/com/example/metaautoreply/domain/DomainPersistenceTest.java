package com.example.metaautoreply.domain;

import com.example.metaautoreply.AbstractIntegrationTest;
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
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trips one row of every entity against a real Postgres.
 *
 * <p>Booting the full context here is the point: it runs Flyway and then Hibernate's
 * {@code ddl-auto: validate} against the migrated schema, so a mismatch between an entity
 * mapping and {@code V1__initial_schema.sql} fails this test rather than production
 * startup. Requires Docker.
 */
@Transactional
class DomainPersistenceTest extends AbstractIntegrationTest {

	@Autowired
	private InboundEventRepository inboundEvents;
	@Autowired
	private ContactRepository contacts;
	@Autowired
	private KeywordRuleRepository rules;
	@Autowired
	private OutboundMessageRepository outbound;
	@Autowired
	private EntityManager em;

	/** Forces a real SQL round trip rather than reading back out of the persistence context. */
	private void roundTrip() {
		em.flush();
		em.clear();
	}

	@Test
	void inboundEventRoundTrips() {
		InboundEvent saved = inboundEvents.save(
				new InboundEvent("comment_1", Platform.IG, "COMMENT", "{\"text\":\"hello\"}"));
		roundTrip();

		InboundEvent found = inboundEvents.findById(saved.getId()).orElseThrow();
		assertThat(found.getEventKey()).isEqualTo("comment_1");
		assertThat(found.getPlatform()).isEqualTo(Platform.IG);
		assertThat(found.getEventType()).isEqualTo("COMMENT");
		// jsonb: Postgres may normalise whitespace, so assert on content not bytes.
		assertThat(found.getPayload()).contains("hello");
		// Defaults applied by the entity, matching the column defaults in V1.
		assertThat(found.getStatus()).isEqualTo(EventStatus.NEW);
		assertThat(found.getAttempts()).isZero();
		assertThat(found.getReceivedAt()).isNotNull();
		assertThat(found.getProcessedAt()).isNull();
	}

	@Test
	void existsByEventKeyBacksIdempotentIntake() {
		inboundEvents.save(new InboundEvent("mid_abc", Platform.FB, "MESSAGE", "{}"));
		roundTrip();

		assertThat(inboundEvents.existsByEventKey("mid_abc")).isTrue();
		assertThat(inboundEvents.existsByEventKey("never_seen")).isFalse();
	}

	@Test
	void contactRoundTripsAndIsFoundByPlatformAndExternalId() {
		Contact saved = contacts.save(new Contact(Platform.IG, "igsid_1", "someone"));
		saved.setLastInteractionAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));
		roundTrip();

		Contact found = contacts.findByPlatformAndExternalId(Platform.IG, "igsid_1").orElseThrow();
		assertThat(found.getId()).isEqualTo(saved.getId());
		assertThat(found.getUsername()).isEqualTo("someone");
		assertThat(found.getLastInteractionAt()).isNotNull();
		assertThat(found.isOptedOut()).isFalse();

		assertThat(contacts.findByPlatformAndExternalId(Platform.FB, "igsid_1"))
				.as("external ids are scoped per platform")
				.isEmpty();
	}

	@Test
	void keywordRuleRoundTrips() {
		KeywordRule saved = rules.save(new KeywordRule(
				"test rule", Platform.FB, TriggerType.COMMENT, MatchType.REGEX,
				"^price\\b", "public", "dm", true, 5));
		roundTrip();

		KeywordRule found = rules.findById(saved.getId()).orElseThrow();
		assertThat(found.getName()).isEqualTo("test rule");
		assertThat(found.getMatchType()).isEqualTo(MatchType.REGEX);
		assertThat(found.getKeyword()).isEqualTo("^price\\b");
		assertThat(found.getPublicReply()).isEqualTo("public");
		assertThat(found.getDmText()).isEqualTo("dm");
		assertThat(found.isEnabled()).isTrue();
		assertThat(found.getPriority()).isEqualTo(5);
	}

	@Test
	void seedRulesFromV2ArePresentAndDisabled() {
		assertThat(rules.findAll())
				.filteredOn(r -> r.getName().startsWith("IG price") || r.getName().startsWith("FB info"))
				.hasSize(2)
				.allSatisfy(r -> assertThat(r.isEnabled()).as("seed rules must ship disabled").isFalse());
	}

	@Test
	void outboundMessageRoundTripsWithSourceEvent() {
		InboundEvent event = inboundEvents.save(
				new InboundEvent("comment_2", Platform.IG, "COMMENT", "{}"));
		OutboundMessage saved = outbound.save(new OutboundMessage(
				OutboundKind.PRIVATE_REPLY, Platform.IG, "comment_2", "hi there", event));
		roundTrip();

		OutboundMessage found = outbound.findById(saved.getId()).orElseThrow();
		assertThat(found.getKind()).isEqualTo(OutboundKind.PRIVATE_REPLY);
		assertThat(found.getTargetId()).isEqualTo("comment_2");
		assertThat(found.getBody()).isEqualTo("hi there");
		assertThat(found.getStatus()).isEqualTo(OutboundStatus.PENDING);
		assertThat(found.getAttempts()).isZero();
		assertThat(found.getNextAttemptAt()).isNotNull();
		assertThat(found.getSentAt()).isNull();
		assertThat(found.getProviderMsgId()).isNull();
		assertThat(found.getSourceEvent().getId()).isEqualTo(event.getId());
	}

	/**
	 * The native {@code for update skip locked} queries are the concurrency backbone of
	 * Phases 6 and 8. Exercising them here catches a malformed query now rather than
	 * inside a scheduler later.
	 */
	@Test
	void lockBatchQueriesReturnClaimableRows() {
		inboundEvents.save(new InboundEvent("comment_3", Platform.IG, "COMMENT", "{}"));
		OutboundMessage due = outbound.save(new OutboundMessage(
				OutboundKind.PUBLIC_REPLY, Platform.IG, "comment_3", "body", null));
		due.setNextAttemptAt(Instant.now().minusSeconds(60));
		roundTrip();

		assertThat(inboundEvents.lockBatch(25))
				.extracting(InboundEvent::getEventKey)
				.contains("comment_3");
		assertThat(outbound.lockBatch(10))
				.extracting(OutboundMessage::getTargetId)
				.contains("comment_3");
	}

	/** A message whose backoff has not elapsed must not be claimed. */
	@Test
	void lockBatchSkipsMessagesNotYetDue() {
		OutboundMessage future = outbound.save(new OutboundMessage(
				OutboundKind.DM, Platform.FB, "psid_future", "later", null));
		future.setNextAttemptAt(Instant.now().plusSeconds(3600));
		roundTrip();

		assertThat(outbound.lockBatch(10))
				.extracting(OutboundMessage::getTargetId)
				.doesNotContain("psid_future");
	}
}
