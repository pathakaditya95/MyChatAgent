package com.example.metaautoreply.ingest.payload;

import com.example.metaautoreply.config.MetaProperties;
import com.example.metaautoreply.domain.InboundEvent;
import com.example.metaautoreply.domain.enums.Platform;
import com.example.metaautoreply.domain.enums.TriggerType;
import com.example.metaautoreply.engine.NormalizedEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs against the real payloads captured in Phase 5, not invented ones.
 *
 * <p>The two platforms disagree on nearly every field name — `field`, the id key, the text key,
 * the display-name key, and whether `verb` exists at all — so these tests are the guard that
 * both paths stay correct.
 */
class EventNormalizerTest {

	private static final String PAGE_ID = "824570447415713";
	private static final String IG_USER_ID = "17841403243590103";

	private final EventNormalizer normalizer = new EventNormalizer(
			JsonMapper.builder().build(),
			new MetaProperties("secret", null, "verify", "token", PAGE_ID, IG_USER_ID,
					"v21.0", "https://graph.facebook.com", true));

	private static String fixture(String name) throws IOException {
		try (InputStream in = EventNormalizerTest.class.getResourceAsStream("/fixtures/" + name)) {
			assertThat(in).as("fixture %s must exist", name).isNotNull();
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private Optional<NormalizedEvent> normalizeFixture(String name, Platform platform) throws IOException {
		InboundEvent event = new InboundEvent("key", platform, "TYPE", fixture(name));
		return normalizer.normalize(event);
	}

	private Optional<NormalizedEvent> normalize(String payload, Platform platform) {
		return normalizer.normalize(new InboundEvent("key", platform, "TYPE", payload));
	}

	// --- Instagram --------------------------------------------------------------------

	@Test
	void normalizesARealInstagramComment() throws IOException {
		NormalizedEvent event = normalizeFixture("ig-comment-1.json", Platform.IG).orElseThrow();

		assertThat(event.platform()).isEqualTo(Platform.IG);
		assertThat(event.triggerType()).isEqualTo(TriggerType.COMMENT);
		assertThat(event.eventKey()).isEqualTo("18002189276993311");
		assertThat(event.senderId()).isEqualTo("694336747088628");
		assertThat(event.senderUsername()).isEqualTo("science_hustler");
		assertThat(event.text()).isEqualTo("Test");
		assertThat(event.parentMediaId()).isEqualTo("18087353516437462");
		assertThat(event.fromSelf()).isFalse();
	}

	/**
	 * ig-message-1 and -2 are echoes of our own outgoing DMs; -3 is the genuine inbound one.
	 * That distribution is itself worth knowing — most captured "messages" were our own.
	 */
	@Test
	void normalizesARealInstagramMessage() throws IOException {
		NormalizedEvent event = normalizeFixture("ig-message-3.json", Platform.IG).orElseThrow();

		assertThat(event.triggerType()).isEqualTo(TriggerType.MESSAGE);
		assertThat(event.senderId()).isEqualTo("694336747088628");
		assertThat(event.text()).isEqualTo("Hi");
		assertThat(event.eventKey()).startsWith("aWdfZAG1faXRlbTo");
		// Messages carry no username, unlike comments.
		assertThat(event.senderUsername()).isNull();
		assertThat(event.occurredAt()).isNotNull();
	}

	/**
	 * The bug this whole phase was designed to avoid: Instagram comments have no `verb`, so a
	 * naive {@code "add".equals(verb)} check discards every one of them and the service does
	 * nothing while looking perfectly healthy.
	 */
	@Test
	void instagramCommentsHaveNoVerbAndMustStillBeActionable() throws IOException {
		assertThat(fixture("ig-comment-1.json")).doesNotContain("verb");
		assertThat(normalizeFixture("ig-comment-1.json", Platform.IG)).isPresent();
	}

	// --- Facebook ---------------------------------------------------------------------

	@Test
	void normalizesARealFacebookComment() throws IOException {
		NormalizedEvent event = normalizeFixture("fb-feed-1.json", Platform.FB).orElseThrow();

		assertThat(event.platform()).isEqualTo(Platform.FB);
		assertThat(event.triggerType()).isEqualTo(TriggerType.COMMENT);
		// comment_id, not id — Instagram uses the other one.
		assertThat(event.eventKey()).isEqualTo("122141626023153354_1386416150303193");
		assertThat(event.senderId()).isEqualTo("27741041858920618");
		// Facebook supplies a display name where Instagram supplies a username.
		assertThat(event.senderUsername()).isEqualTo("Swati Shri Pal Singh");
		// value.message, not value.text.
		assertThat(event.text()).isEqualTo("Grfg");
		assertThat(event.parentMediaId()).isEqualTo("824570447415713_122141626023153354");
		assertThat(event.fromSelf()).isFalse();
	}

	/** created_time is epoch seconds; reading it as millis would place it in 1970. */
	@Test
	void facebookCreatedTimeIsInterpretedAsSeconds() throws IOException {
		NormalizedEvent event = normalizeFixture("fb-feed-1.json", Platform.FB).orElseThrow();

		assertThat(event.occurredAt()).isEqualTo(Instant.ofEpochSecond(1786796286));
		assertThat(event.occurredAt()).isAfter(Instant.parse("2020-01-01T00:00:00Z"));
	}

	@Test
	void normalizesARealFacebookMessage() throws IOException {
		NormalizedEvent event = normalizeFixture("fb-message-1.json", Platform.FB).orElseThrow();

		assertThat(event.triggerType()).isEqualTo(TriggerType.MESSAGE);
		assertThat(event.senderId()).isEqualTo("27741041858920618");
		assertThat(event.text()).isEqualTo("Gu");
		assertThat(event.eventKey()).startsWith("m_");
	}

	/**
	 * A Facebook `feed` change covers posts, reactions and shares too. Without the
	 * {@code item == "comment"} discriminator the service would react to likes and to its own
	 * posts.
	 */
	@Test
	void ignoresFeedChangesThatAreNotComments() {
		String reaction = """
				{"field":"feed","value":{"item":"reaction","verb":"add","from":{"id":"1","name":"X"},
				"message":"irrelevant","post_id":"p1","comment_id":"c1"}}""";
		String post = """
				{"field":"feed","value":{"item":"post","verb":"add","from":{"id":"1","name":"X"},
				"message":"a new post","post_id":"p1"}}""";

		assertThat(normalize(reaction, Platform.FB)).isEmpty();
		assertThat(normalize(post, Platform.FB)).isEmpty();
	}

	@Test
	void ignoresEditedAndRemovedComments() {
		for (String verb : new String[] {"edited", "remove", "hide"}) {
			String payload = """
					{"field":"feed","value":{"item":"comment","verb":"%s","from":{"id":"1","name":"X"},
					"message":"hello","post_id":"p1","comment_id":"c1"}}""".formatted(verb);
			assertThat(normalize(payload, Platform.FB)).as("verb=%s", verb).isEmpty();
		}
	}

	// --- Cross-cutting filters --------------------------------------------------------

	/** Compliance invariant #4 — otherwise the service replies to itself forever. */
	@Test
	void flagsOurOwnCommentsAsFromSelf() {
		String fromPage = """
				{"field":"feed","value":{"item":"comment","verb":"add","from":{"id":"%s","name":"Us"},
				"message":"our own reply","post_id":"p1","comment_id":"c9"}}""".formatted(PAGE_ID);
		String fromIg = """
				{"field":"comments","value":{"id":"c8","from":{"id":"%s","username":"us"},"text":"ours"}}"""
				.formatted(IG_USER_ID);

		assertThat(normalize(fromPage, Platform.FB).orElseThrow().fromSelf()).isTrue();
		assertThat(normalize(fromIg, Platform.IG).orElseThrow().fromSelf()).isTrue();
	}

	@Test
	void ignoresCommentsWithNoText() {
		String noText = """
				{"field":"comments","value":{"id":"c1","from":{"id":"u1","username":"x"}}}""";
		String blankText = """
				{"field":"comments","value":{"id":"c1","from":{"id":"u1","username":"x"},"text":"   "}}""";

		assertThat(normalize(noText, Platform.IG)).isEmpty();
		assertThat(normalize(blankText, Platform.IG)).isEmpty();
	}

	/**
	 * Meta echoes our own outgoing DMs back as webhook events. Acting on one would have the
	 * service talking to itself — compliance invariant #4, and the reason two of the three
	 * captured Instagram message fixtures are echoes rather than real inbound traffic.
	 */
	@Test
	void ignoresEchoesOfOurOwnMessages() throws IOException {
		assertThat(fixture("ig-message-1.json")).contains("\"is_echo\": true");
		assertThat(normalizeFixture("ig-message-1.json", Platform.IG)).isEmpty();
		assertThat(normalizeFixture("ig-message-2.json", Platform.IG)).isEmpty();
	}

	/** An echo carrying text must still be refused, not merely one that happens to lack text. */
	@Test
	void ignoresEchoesEvenWhenTheyCarryText() {
		String echoWithText = """
				{"sender":{"id":"%s"},"recipient":{"id":"u1"},
				"message":{"mid":"m1","is_echo":true,"text":"our own reply"},"timestamp":1786796190078}"""
				.formatted(IG_USER_ID);

		assertThat(normalize(echoWithText, Platform.IG)).isEmpty();
	}

	@Test
	void ignoresAttachmentOnlyMessages() {
		String attachmentOnly = """
				{"sender":{"id":"u1"},"recipient":{"id":"p1"},
				"message":{"mid":"m1","attachments":[{"type":"image"}]},"timestamp":1786796190078}""";

		assertThat(normalize(attachmentOnly, Platform.IG)).isEmpty();
	}

	@Test
	void ignoresUnparseableAndUnknownShapes() {
		assertThat(normalize("not json at all", Platform.IG)).isEmpty();
		assertThat(normalize("{\"something\":\"unexpected\"}", Platform.FB)).isEmpty();
	}
}
