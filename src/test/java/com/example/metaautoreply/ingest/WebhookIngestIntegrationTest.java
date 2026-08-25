package com.example.metaautoreply.ingest;

import com.example.metaautoreply.AbstractIntegrationTest;
import com.example.metaautoreply.domain.InboundEvent;
import com.example.metaautoreply.domain.enums.EventStatus;
import com.example.metaautoreply.domain.enums.Platform;
import com.example.metaautoreply.repo.InboundEventRepository;
import com.example.metaautoreply.security.SignatureVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end intake: a signed HTTP POST all the way to rows in {@code inbound_event}.
 *
 * <p>Payload shapes here are the documented webhook envelope only. Per {@code PLAN.md} rule 2,
 * these are <em>not</em> a substitute for the real captures Phase 5 produces — they exercise
 * splitting and idempotency, not field semantics.
 */
@AutoConfigureMockMvc
class WebhookIngestIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private SignatureVerifier signatureVerifier;
	@Autowired
	private InboundEventRepository events;

	@BeforeEach
	void clean() {
		events.deleteAll();
	}

	private void postSigned(String body, int expectedStatus) throws Exception {
		mockMvc.perform(post("/webhook")
						.contentType(MediaType.APPLICATION_JSON)
						.header("X-Hub-Signature-256", signatureVerifier.sign(body))
						.content(body))
				.andExpect(status().is(expectedStatus));
	}

	@Test
	void aSignedDeliveryInsertsARow() throws Exception {
		String body = """
				{"object":"instagram","entry":[{"id":"17841400000000000","time":1700000000,
				"changes":[{"field":"comments","value":{"id":"comment_aaa","text":"what is the price?"}}]}]}""";

		postSigned(body, 200);

		List<InboundEvent> stored = events.findAll();
		assertThat(stored).hasSize(1);
		InboundEvent event = stored.get(0);
		assertThat(event.getEventKey()).isEqualTo("comment_aaa");
		assertThat(event.getEventType()).isEqualTo("COMMENT");
		assertThat(event.getPlatform()).isEqualTo(Platform.IG);
		assertThat(event.getStatus()).isEqualTo(EventStatus.NEW);
		assertThat(event.getPayload()).contains("what is the price?");
	}

	@Test
	void anInvalidSignatureIsRejectedAndStoresNothing() throws Exception {
		String body = """
				{"object":"page","entry":[{"id":"1","changes":[{"field":"feed",
				"value":{"comment_id":"comment_bbb","message":"info"}}]}]}""";

		mockMvc.perform(post("/webhook")
						.contentType(MediaType.APPLICATION_JSON)
						.header("X-Hub-Signature-256", "sha256=" + "0".repeat(64))
						.content(body))
				.andExpect(status().isForbidden());

		assertThat(events.findAll()).isEmpty();
	}

	/** Meta redelivers freely; the unique event_key must collapse repeats into one row. */
	@Test
	void theSamePayloadPostedTwiceInsertsExactlyOneRow() throws Exception {
		String body = """
				{"object":"instagram","entry":[{"id":"1","changes":[{"field":"comments",
				"value":{"id":"comment_ccc","text":"price?"}}]}]}""";

		postSigned(body, 200);
		postSigned(body, 200);

		assertThat(events.findAll()).hasSize(1);
	}

	@Test
	void multipleChangesInOneDeliveryBecomeSeparateRows() throws Exception {
		String body = """
				{"object":"instagram","entry":[{"id":"1","changes":[
				{"field":"comments","value":{"id":"comment_d1","text":"one"}},
				{"field":"comments","value":{"id":"comment_d2","text":"two"}}]}]}""";

		postSigned(body, 200);

		assertThat(events.findAll())
				.extracting(InboundEvent::getEventKey)
				.containsExactlyInAnyOrder("comment_d1", "comment_d2");
	}

	@Test
	void messagingEntriesAreKeyedByMid() throws Exception {
		String body = """
				{"object":"page","entry":[{"id":"1","messaging":[
				{"sender":{"id":"psid_1"},"message":{"mid":"mid_eee","text":"hello"}}]}]}""";

		postSigned(body, 200);

		List<InboundEvent> stored = events.findAll();
		assertThat(stored).hasSize(1);
		assertThat(stored.get(0).getEventKey()).isEqualTo("mid_eee");
		assertThat(stored.get(0).getEventType()).isEqualTo("MESSAGE");
		assertThat(stored.get(0).getPlatform()).isEqualTo(Platform.FB);
	}

	/**
	 * An unrecognised shape must still be stored — Phase 5 mines these rows for fixtures, so
	 * silently dropping them would hide exactly what we need to see.
	 */
	@Test
	void anUnrecognisedShapeIsStoredAsUnknownRatherThanDropped() throws Exception {
		String body = "{\"object\":\"page\",\"entry\":[{\"id\":\"1\",\"something_new\":{\"a\":1}}]}";

		postSigned(body, 200);

		List<InboundEvent> stored = events.findAll();
		assertThat(stored).hasSize(1);
		assertThat(stored.get(0).getEventType()).isEqualTo("UNKNOWN");
		// Falls back to a content hash, so redelivery still collapses to one row.
		assertThat(stored.get(0).getEventKey()).hasSize(64);
	}

	@Test
	void aBodyWithNoEntryArrayIsStoredWhole() throws Exception {
		String body = "{\"object\":\"page\"}";

		postSigned(body, 200);

		assertThat(events.findAll())
				.singleElement()
				.satisfies(e -> assertThat(e.getEventType()).isEqualTo("UNKNOWN"));
	}

	/** Malformed JSON survives the signature check, so intake must swallow it and still ack. */
	@Test
	void malformedJsonStillReturns200() throws Exception {
		postSigned("this is not json", 200);

		assertThat(events.findAll()).isEmpty();
	}
}
