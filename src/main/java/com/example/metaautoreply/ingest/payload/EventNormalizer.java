package com.example.metaautoreply.ingest.payload;

import com.example.metaautoreply.config.MetaProperties;
import com.example.metaautoreply.domain.InboundEvent;
import com.example.metaautoreply.domain.enums.Platform;
import com.example.metaautoreply.domain.enums.TriggerType;
import com.example.metaautoreply.engine.NormalizedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Optional;

/**
 * Turns a stored payload into a {@link NormalizedEvent}, or {@link Optional#empty()} when the
 * event is not actionable.
 *
 * <p>Written strictly against the fixtures captured in Phase 5
 * (`src/test/resources/fixtures/`). The two platforms disagree on nearly every field name, so
 * each is handled separately rather than through a hopeful shared path — see the comparison
 * table in `NOTES.md`.
 */
@Component
public class EventNormalizer {

	private static final Logger log = LoggerFactory.getLogger(EventNormalizer.class);

	private final ObjectMapper mapper;
	private final String pageId;
	private final String igUserId;

	public EventNormalizer(ObjectMapper mapper, MetaProperties props) {
		this.mapper = mapper;
		this.pageId = props.pageId();
		this.igUserId = props.igUserId();
	}

	public Optional<NormalizedEvent> normalize(InboundEvent event) {
		JsonNode root;
		try {
			root = mapper.readTree(event.getPayload());
		}
		catch (RuntimeException e) {
			log.warn("Event {} has an unparseable payload; skipping", event.getEventKey());
			return Optional.empty();
		}

		// A "changes" entry carries `field`; a messaging entry carries `message`.
		if (!root.path("field").isMissingNode()) {
			return normalizeComment(event.getPlatform(), root, event.getReceivedAt());
		}
		if (root.path("message").isObject()) {
			return normalizeMessage(event.getPlatform(), root);
		}
		log.debug("Event {} matches no known shape; skipping", event.getEventKey());
		return Optional.empty();
	}

	private Optional<NormalizedEvent> normalizeComment(Platform platform, JsonNode root, Instant receivedAt) {
		JsonNode value = root.path("value");

		// Facebook sends verb=add/edited/remove; Instagram sends no verb at all. Treating a
		// missing verb as non-add would discard every Instagram comment, so only filter when
		// the field is actually present.
		String verb = value.path("verb").asString(null);
		if (verb != null && !verb.equalsIgnoreCase("add")) {
			log.debug("Ignoring comment with verb '{}'", verb);
			return Optional.empty();
		}

		String commentId;
		String text;
		String username;
		String parentId;
		Instant occurredAt;

		if (platform == Platform.IG) {
			commentId = value.path("id").asString(null);
			text = value.path("text").asString(null);
			username = value.path("from").path("username").asString(null);
			parentId = value.path("media").path("id").asString(null);
			// Instagram comments carry no timestamp. We receive events in near real time, so
			// arrival is a sound proxy for the 7-day private-reply window.
			occurredAt = receivedAt;
		}
		else {
			// A Facebook `feed` change also covers posts, reactions, shares and edits.
			// Without this discriminator the service would react to its own posts and to likes.
			if (!"comment".equals(value.path("item").asString(null))) {
				log.debug("Ignoring feed change of type '{}'", value.path("item").asString("?"));
				return Optional.empty();
			}
			commentId = value.path("comment_id").asString(null);
			text = value.path("message").asString(null);
			username = value.path("from").path("name").asString(null);
			parentId = value.path("post_id").asString(null);
			// created_time is epoch SECONDS here, unlike the millisecond messaging timestamp.
			occurredAt = value.path("created_time").isNumber()
					? Instant.ofEpochSecond(value.path("created_time").asLong())
					: receivedAt;
		}

		String senderId = value.path("from").path("id").asString(null);
		if (isBlank(commentId) || isBlank(senderId) || isBlank(text)) {
			log.debug("Ignoring comment with no id, sender or text");
			return Optional.empty();
		}

		return Optional.of(new NormalizedEvent(platform, TriggerType.COMMENT, commentId, senderId,
				username, text, parentId, isSelf(senderId), occurredAt));
	}

	private Optional<NormalizedEvent> normalizeMessage(Platform platform, JsonNode root) {
		// Meta echoes our own outgoing DMs back to us as webhook events (`is_echo: true`,
		// sender = our own account). Replying to those is a direct route to an infinite loop.
		// The fromSelf check would also catch it, but this is the explicit signal and does not
		// depend on META_PAGE_ID / META_IG_USER_ID being configured correctly.
		if (root.path("message").path("is_echo").asBoolean(false)) {
			log.debug("Ignoring echo of our own message");
			return Optional.empty();
		}

		String mid = root.path("message").path("mid").asString(null);
		String senderId = root.path("sender").path("id").asString(null);
		String text = root.path("message").path("text").asString(null);

		// Attachment-only messages have no text; there is nothing to match a keyword against.
		if (isBlank(mid) || isBlank(senderId) || isBlank(text)) {
			log.debug("Ignoring message with no mid, sender or text");
			return Optional.empty();
		}

		Instant occurredAt = root.path("timestamp").isNumber()
				? Instant.ofEpochMilli(root.path("timestamp").asLong())
				: null;

		return Optional.of(new NormalizedEvent(platform, TriggerType.MESSAGE, mid, senderId,
				null, text, null, isSelf(senderId), occurredAt));
	}

	/**
	 * Compliance invariant #4. An event sent by our own Page or Instagram account must never be
	 * replied to, or the service answers itself in a loop.
	 */
	private boolean isSelf(String senderId) {
		return senderId.equals(pageId) || senderId.equals(igUserId);
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
