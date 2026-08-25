package com.example.metaautoreply.ingest;

import com.example.metaautoreply.domain.InboundEvent;
import com.example.metaautoreply.domain.enums.Platform;
import com.example.metaautoreply.repo.InboundEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Splits a webhook delivery into individual {@code inbound_event} rows.
 *
 * <p>This class does the least parsing it can get away with. It walks the delivery envelope
 * — {@code entry[]}, then {@code changes[]} and {@code messaging[]} — and stores each element
 * verbatim. It deliberately does <em>not</em> interpret the contents: per {@code PLAN.md}
 * rule 2, real payloads are captured in Phase 5 and the normalizer is written against those
 * fixtures in Phase 6. Anything this class cannot confidently identify is stored as
 * {@code UNKNOWN} rather than guessed at, so nothing is silently dropped.
 */
@Service
public class WebhookIngestService {

	private static final Logger log = LoggerFactory.getLogger(WebhookIngestService.class);

	static final String TYPE_COMMENT = "COMMENT";
	static final String TYPE_MESSAGE = "MESSAGE";
	static final String TYPE_UNKNOWN = "UNKNOWN";

	private final InboundEventRepository events;
	private final ObjectMapper mapper;

	public WebhookIngestService(InboundEventRepository events, ObjectMapper mapper) {
		this.events = events;
		this.mapper = mapper;
	}

	/**
	 * @param rawBody the verified request body
	 * @return how many new rows were inserted; redeliveries count as zero
	 */
	public int ingest(String rawBody) {
		JsonNode root = mapper.readTree(rawBody);
		Platform platform = platformOf(root);

		int inserted = 0;
		JsonNode entries = root.path("entry");
		if (!entries.isArray() || entries.isEmpty()) {
			// Not an envelope we recognise. Keep the whole body rather than lose it.
			return store(rawBody, platform, TYPE_UNKNOWN, null);
		}

		for (JsonNode entry : entries) {
			int before = inserted;

			for (JsonNode change : entry.path("changes")) {
				inserted += store(change.toString(), platform, changeType(change), commentId(change));
			}
			for (JsonNode messaging : entry.path("messaging")) {
				inserted += store(messaging.toString(), platform, TYPE_MESSAGE, messageId(messaging));
			}

			// An entry carrying neither changes[] nor messaging[] is a shape we have not seen.
			// Store the entry itself so Phase 5 can inspect it.
			if (inserted == before && !entry.path("changes").isArray() && !entry.path("messaging").isArray()) {
				inserted += store(entry.toString(), platform, TYPE_UNKNOWN, null);
			}
		}
		return inserted;
	}

	/**
	 * Inserts one event, tolerating redelivery.
	 *
	 * <p>{@code save} runs in its own transaction, so a unique-key collision rolls back only
	 * this row and the rest of the batch still lands.
	 *
	 * @param eventKey the natural id, or null to fall back to a hash of the payload
	 * @return 1 if a row was inserted, 0 if it was already present
	 */
	private int store(String payload, Platform platform, String eventType, String eventKey) {
		String key = (eventKey != null && !eventKey.isBlank()) ? eventKey : sha256(payload);

		if (events.existsByEventKey(key)) {
			log.debug("Skipping already-stored event {}", key);
			return 0;
		}
		try {
			events.save(new InboundEvent(key, platform, eventType, payload));
			return 1;
		}
		catch (DataIntegrityViolationException e) {
			// Lost a race with a concurrent redelivery. The row exists either way.
			log.debug("Concurrent insert for event {}, dropping duplicate", key);
			return 0;
		}
	}

	/**
	 * TODO(human): Phase 5 must confirm whether Instagram deliveries arrive with
	 * {@code object: "instagram"} or {@code object: "page"}. Until a real payload settles it,
	 * anything not explicitly Instagram is recorded as FB — the platform is re-derived from
	 * the payload during normalization in Phase 6, so a wrong guess here is recoverable.
	 */
	private Platform platformOf(JsonNode root) {
		String object = root.path("object").asString("");
		return object.toLowerCase().contains("instagram") ? Platform.IG : Platform.FB;
	}

	/** TODO(human): confirm the exact field values against Phase 5 fixtures. */
	private String changeType(JsonNode change) {
		String field = change.path("field").asString("");
		if (field.toLowerCase().contains("comment")) {
			return TYPE_COMMENT;
		}
		return field.isBlank() ? TYPE_UNKNOWN : field.toUpperCase();
	}

	/**
	 * Best-effort comment id. Returning null is safe — the caller falls back to hashing the
	 * payload, which still gives a stable idempotency key.
	 *
	 * <p>TODO(human): replace the candidate list with the single real path once Phase 5
	 * captures a genuine comment payload.
	 */
	private String commentId(JsonNode change) {
		JsonNode value = change.path("value");
		String commentId = value.path("comment_id").asString(null);
		return commentId != null ? commentId : value.path("id").asString(null);
	}

	/** TODO(human): confirm against a real message payload in Phase 5. */
	private String messageId(JsonNode messaging) {
		return messaging.path("message").path("mid").asString(null);
	}

	private String sha256(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}
}
