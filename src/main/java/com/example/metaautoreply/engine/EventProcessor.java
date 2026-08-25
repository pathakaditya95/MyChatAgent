package com.example.metaautoreply.engine;

import com.example.metaautoreply.domain.Contact;
import com.example.metaautoreply.domain.InboundEvent;
import com.example.metaautoreply.domain.KeywordRule;
import com.example.metaautoreply.domain.OutboundMessage;
import com.example.metaautoreply.domain.enums.EventStatus;
import com.example.metaautoreply.domain.enums.OutboundKind;
import com.example.metaautoreply.domain.enums.TriggerType;
import com.example.metaautoreply.ingest.payload.EventNormalizer;
import com.example.metaautoreply.repo.ContactRepository;
import com.example.metaautoreply.repo.InboundEventRepository;
import com.example.metaautoreply.repo.OutboundMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Drains {@code inbound_event} and turns matching events into queued outbound messages.
 *
 * <p>Nothing here talks to Meta. It only decides what should be sent; the dispatcher decides
 * when, and does the sending.
 */
@Component
public class EventProcessor {

	private static final Logger log = LoggerFactory.getLogger(EventProcessor.class);

	private static final int BATCH_SIZE = 25;
	private static final int MAX_ATTEMPTS = 3;

	/** Compliance invariant #2: a comment older than this cannot receive a private reply. */
	private static final Duration PRIVATE_REPLY_WINDOW = Duration.ofDays(7);

	/**
	 * Compliance invariant #5. Word-boundary matching on purpose — a substring test would
	 * opt out anyone who wrote "stopwatch" or "nonstop".
	 */
	private static final Pattern OPT_OUT = Pattern.compile("\\b(stop|unsubscribe)\\b",
			Pattern.CASE_INSENSITIVE);

	private final InboundEventRepository events;
	private final ContactRepository contacts;
	private final OutboundMessageRepository outbound;
	private final EventNormalizer normalizer;
	private final RuleMatcher ruleMatcher;

	public EventProcessor(InboundEventRepository events, ContactRepository contacts,
			OutboundMessageRepository outbound, EventNormalizer normalizer, RuleMatcher ruleMatcher) {
		this.events = events;
		this.contacts = contacts;
		this.outbound = outbound;
		this.normalizer = normalizer;
		this.ruleMatcher = ruleMatcher;
	}

	/**
	 * Claims a batch of new events and processes each one.
	 *
	 * <p>{@code @Transactional} is load-bearing: {@code lockBatch} takes {@code FOR UPDATE SKIP
	 * LOCKED} row locks that are released the moment the transaction ends, so without it two
	 * instances would happily process the same rows.
	 */
	@Scheduled(fixedDelayString = "${autoreply.process-interval-ms}")
	@Transactional
	public void processBatch() {
		List<InboundEvent> batch = events.lockBatch(BATCH_SIZE);
		if (batch.isEmpty()) {
			return;
		}
		log.debug("Processing {} event(s)", batch.size());
		for (InboundEvent event : batch) {
			processOne(event);
		}
	}

	private void processOne(InboundEvent event) {
		try {
			Optional<NormalizedEvent> normalized = normalizer.normalize(event);
			if (normalized.isEmpty()) {
				skip(event, "not actionable");
				return;
			}
			NormalizedEvent normalizedEvent = normalized.get();

			// Compliance invariant #4. Checked before touching Contact so we never record
			// ourselves as someone to talk to.
			if (normalizedEvent.fromSelf()) {
				skip(event, "sent by our own account");
				return;
			}

			Contact contact = upsertContact(normalizedEvent);
			if (contact.isOptedOut()) {
				skip(event, "contact has opted out");
				return;
			}

			Optional<KeywordRule> rule = ruleMatcher.match(normalizedEvent);
			if (rule.isEmpty()) {
				skip(event, "no matching rule");
				return;
			}

			queueOutbound(event, normalizedEvent, rule.get());
			event.setStatus(EventStatus.DONE);
			event.setProcessedAt(Instant.now());
			events.save(event);
		}
		catch (Exception e) {
			recordFailure(event, e);
		}
	}

	/**
	 * Creates or refreshes the contact, and honours opt-out keywords.
	 *
	 * <p>{@code last_interaction_at} is what compliance invariant #3 (the 24-hour messaging
	 * window) is measured against, so it is stamped on every inbound event.
	 */
	private Contact upsertContact(NormalizedEvent event) {
		Contact contact = contacts
				.findByPlatformAndExternalId(event.platform(), event.senderId())
				.orElseGet(() -> new Contact(event.platform(), event.senderId(), event.senderUsername()));

		contact.setLastInteractionAt(Instant.now());
		if (event.senderUsername() != null) {
			contact.setUsername(event.senderUsername());
		}
		// Compliance invariant #5: opting out is permanent until cleared by hand.
		if (!contact.isOptedOut() && OPT_OUT.matcher(event.text()).find()) {
			log.info("Contact {} opted out via '{}'", event.senderId(), event.text());
			contact.setOptedOut(true);
		}
		return contacts.save(contact);
	}

	private void queueOutbound(InboundEvent source, NormalizedEvent event, KeywordRule rule) {
		if (event.triggerType() == TriggerType.COMMENT) {
			if (rule.getPublicReply() != null) {
				enqueue(OutboundKind.PUBLIC_REPLY, event, rule.getPublicReply(), source);
			}
			if (rule.getDmText() != null) {
				if (withinPrivateReplyWindow(event)) {
					enqueue(OutboundKind.PRIVATE_REPLY, event, rule.getDmText(), source);
				}
				else {
					log.info("Skipping private reply to comment {}: outside the {}-day window",
							event.eventKey(), PRIVATE_REPLY_WINDOW.toDays());
				}
			}
		}
		else if (rule.getDmText() != null) {
			// A reply to an inbound message. The 24-hour window (invariant #3) is satisfied by
			// construction: they just messaged us. Targeted by sender, not by message id,
			// because that is who the DM goes to.
			enqueue(OutboundKind.DM, event, rule.getDmText(), source);
		}
	}

	/** Compliance invariant #2. */
	private boolean withinPrivateReplyWindow(NormalizedEvent event) {
		if (event.occurredAt() == null) {
			return true;
		}
		return Duration.between(event.occurredAt(), Instant.now()).compareTo(PRIVATE_REPLY_WINDOW) < 0;
	}

	/**
	 * Queues one message, unless an identical one already exists.
	 *
	 * <p>The pre-check keeps the redelivery case cheap. The
	 * {@code unique (kind, target_id)} constraint remains the real guarantee — invariant #1,
	 * one private reply per comment — and a lost race there rolls the batch back so the events
	 * are simply retried.
	 */
	private void enqueue(OutboundKind kind, NormalizedEvent event, String body, InboundEvent source) {
		String targetId = (kind == OutboundKind.DM) ? event.senderId() : event.eventKey();

		if (outbound.existsByKindAndTargetId(kind, targetId)) {
			log.debug("{} for {} already queued or sent; skipping", kind, targetId);
			return;
		}
		outbound.save(new OutboundMessage(kind, event.platform(), targetId, body, source));
		log.debug("Queued {} for {}", kind, targetId);
	}

	private void skip(InboundEvent event, String reason) {
		log.debug("Skipping event {}: {}", event.getEventKey(), reason);
		event.setStatus(EventStatus.SKIPPED);
		event.setProcessedAt(Instant.now());
		event.setLastError(reason);
		events.save(event);
	}

	private void recordFailure(InboundEvent event, Exception e) {
		int attempts = event.getAttempts() + 1;
		event.setAttempts(attempts);
		event.setLastError(truncate(e.toString()));
		if (attempts >= MAX_ATTEMPTS) {
			event.setStatus(EventStatus.FAILED);
			event.setProcessedAt(Instant.now());
			log.error("Event {} failed {} times; giving up", event.getEventKey(), attempts, e);
		}
		else {
			// Left NEW so the next tick retries it.
			log.warn("Event {} failed (attempt {}/{}); will retry", event.getEventKey(), attempts, MAX_ATTEMPTS, e);
		}
		events.save(event);
	}

	private static String truncate(String message) {
		return message.length() <= 1000 ? message : message.substring(0, 1000);
	}
}
