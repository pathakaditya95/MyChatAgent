package com.example.metaautoreply.web;

import com.example.metaautoreply.domain.InboundEvent;
import com.example.metaautoreply.repo.InboundEventRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;

/**
 * Read-only window onto captured webhook traffic, used during Phase 5 to work out the real
 * shape of Meta's payloads before any parser is written.
 *
 * <p>Guarded by {@code @Profile("debug")}: these responses contain raw payloads including
 * commenter ids, usernames and message text, so the endpoint must not exist in production.
 * Run with {@code SPRING_PROFILES_ACTIVE=debug} to switch it on.
 */
@RestController
@RequestMapping("/debug")
@Profile("debug")
public class DebugController {

	private static final int MAX_LIMIT = 500;

	private final InboundEventRepository events;
	private final ObjectMapper mapper;

	public DebugController(InboundEventRepository events, ObjectMapper mapper) {
		this.events = events;
		this.mapper = mapper;
	}

	/**
	 * Most recent events, newest first, as pretty-printed JSON.
	 *
	 * <p>The stored payload is spliced back in as real JSON rather than an escaped string, so
	 * the output can be read directly and copied straight into a fixture file.
	 */
	@GetMapping(value = "/events", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<String> recentEvents(@RequestParam(defaultValue = "50") int limit) {
		int capped = Math.clamp(limit, 1, MAX_LIMIT);
		List<InboundEvent> rows = events.findAllByOrderByReceivedAtDesc(PageRequest.of(0, capped));

		ArrayNode out = mapper.createArrayNode();
		for (InboundEvent event : rows) {
			ObjectNode node = mapper.createObjectNode();
			node.put("id", event.getId());
			node.put("eventKey", event.getEventKey());
			node.put("platform", event.getPlatform().name());
			node.put("eventType", event.getEventType());
			node.put("status", event.getStatus().name());
			node.put("attempts", event.getAttempts());
			node.put("lastError", event.getLastError());
			// Instant must be stringified explicitly, but a null timestamp has to stay a
			// JSON null — String.valueOf would render the text "null" and read as a value.
			putInstant(node, "receivedAt", event.getReceivedAt());
			putInstant(node, "processedAt", event.getProcessedAt());
			node.set("payload", parseOrWrap(event.getPayload()));
			out.add(node);
		}
		return ResponseEntity.ok(pretty(out));
	}

	/**
	 * How many of each event type have arrived. This is the Phase 5 checklist: keep producing
	 * interactions until every type you care about shows a non-zero count.
	 */
	@GetMapping(value = "/events/types", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<String> eventTypeCounts() {
		ObjectNode out = mapper.createObjectNode();
		events.countByEventType().forEach(row -> out.put(row.getType(), row.getCount()));
		return ResponseEntity.ok(pretty(out));
	}

	private static void putInstant(ObjectNode node, String field, Instant value) {
		if (value == null) {
			node.putNull(field);
		}
		else {
			node.put(field, value.toString());
		}
	}

	/** A payload that will not parse is still worth seeing, so wrap rather than fail. */
	private JsonNode parseOrWrap(String payload) {
		try {
			return mapper.readTree(payload);
		}
		catch (RuntimeException e) {
			return mapper.createObjectNode().put("unparseable", payload);
		}
	}

	private String pretty(JsonNode node) {
		return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
	}
}
