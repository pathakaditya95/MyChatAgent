package com.example.metaautoreply.security;

import com.example.metaautoreply.config.MetaProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests — no Spring context, no database.
 *
 * <p>The expected signature below is a known-answer vector computed independently of this
 * codebase (Python's {@code hmac.new(secret, body, sha256).hexdigest()}). Comparing against
 * a value the verifier produced itself would only prove self-consistency, not correctness.
 */
class SignatureVerifierTest {

	private static final String SECRET = "test_app_secret";
	private static final String BODY = "{\"object\":\"page\",\"entry\":[]}";
	private static final String EXPECTED =
			"sha256=5da47c9c7409e70d7a1851f0cfd9bc27786bd99587a9bf1dd5b51e23a2de03cf";

	private static final String IG_SECRET = "test_instagram_app_secret";

	private final SignatureVerifier verifier = new SignatureVerifier(props(SECRET, null));

	private static MetaProperties props(String appSecret, String instagramAppSecret) {
		return new MetaProperties(appSecret, instagramAppSecret, "verify", "token", "page", "ig",
				"v21.0", "https://graph.facebook.com", true);
	}

	@Test
	void knownBodyAndSecretProduceTheExpectedSignature() {
		assertThat(verifier.sign(BODY)).isEqualTo(EXPECTED);
	}

	@Test
	void acceptsAMatchingSignature() {
		assertThat(verifier.verify(BODY, EXPECTED)).isTrue();
	}

	@Test
	void rejectsATamperedBody() {
		String tampered = "{\"object\":\"page\",\"entry\":[1]}";
		assertThat(verifier.verify(tampered, EXPECTED)).isFalse();
	}

	@Test
	void rejectsASignatureMadeWithTheWrongSecret() {
		String forged = new SignatureVerifier(props("not_the_app_secret", null)).sign(BODY);
		assertThat(verifier.verify(BODY, forged)).isFalse();
	}

	/**
	 * An app using Instagram API with Instagram Login signs Page deliveries with the Facebook
	 * App Secret and Instagram deliveries with the Instagram App Secret. Both must be accepted
	 * — verifying against only one silently refuses every delivery from the other network,
	 * which is precisely how Facebook events went missing during Phase 5 capture.
	 */
	@Test
	void acceptsSignaturesFromEitherConfiguredSecret() {
		SignatureVerifier dual = new SignatureVerifier(props(SECRET, IG_SECRET));

		String signedWithFacebookSecret = new SignatureVerifier(props(SECRET, null)).sign(BODY);
		String signedWithInstagramSecret = new SignatureVerifier(props(IG_SECRET, null)).sign(BODY);

		assertThat(dual.verify(BODY, signedWithFacebookSecret)).as("Page delivery").isTrue();
		assertThat(dual.verify(BODY, signedWithInstagramSecret)).as("Instagram delivery").isTrue();
	}

	@Test
	void aSecondSecretDoesNotWeakenRejection() {
		SignatureVerifier dual = new SignatureVerifier(props(SECRET, IG_SECRET));
		String forged = new SignatureVerifier(props("neither_secret", null)).sign(BODY);

		assertThat(dual.verify(BODY, forged)).isFalse();
		assertThat(dual.verify("{\"tampered\":true}", EXPECTED)).isFalse();
	}

	/**
	 * Configuring the same value twice must not be reported as two keys — that reads like the
	 * setup is complete when only one distinct secret exists, which is exactly the confusion
	 * that occurred during Phase 5.
	 */
	@Test
	void duplicateSecretIsCollapsedNotCountedTwice() {
		SignatureVerifier duplicated = new SignatureVerifier(props(SECRET, SECRET));

		assertThat(duplicated.configuredSecretCount()).isEqualTo(1);
		assertThat(duplicated.verify(BODY, EXPECTED)).as("still verifies normally").isTrue();
	}

	@Test
	void twoDistinctSecretsAreBothCounted() {
		assertThat(new SignatureVerifier(props(SECRET, IG_SECRET)).configuredSecretCount()).isEqualTo(2);
		assertThat(new SignatureVerifier(props(SECRET, null)).configuredSecretCount()).isEqualTo(1);
	}

	/**
	 * A blank Instagram secret means "not configured", not "register the blank string as a
	 * second key". Signing uses the byte[] overload because {@link MetaProperties} rightly
	 * refuses to hold a blank primary secret at all. (A zero-length key is not testable here:
	 * {@code Mac.init} rejects it outright.)
	 */
	@Test
	void blankSecondSecretIsIgnored() {
		SignatureVerifier withBlank = new SignatureVerifier(props(SECRET, "   "));
		String signedWithBlankKey = withBlank.sign(BODY, "   ".getBytes(StandardCharsets.UTF_8));

		assertThat(withBlank.verify(BODY, EXPECTED)).as("primary still works").isTrue();
		assertThat(withBlank.verify(BODY, signedWithBlankKey)).as("blank key not accepted").isFalse();
	}

	@Test
	void rejectsAMissingOrMalformedHeader() {
		assertThat(verifier.verify(BODY, null)).as("absent header").isFalse();
		assertThat(verifier.verify(BODY, "")).as("empty header").isFalse();
		assertThat(verifier.verify(BODY, "sha1=abcdef")).as("wrong algorithm prefix").isFalse();
		assertThat(verifier.verify(BODY, EXPECTED.substring(PREFIX_LEN)))
				.as("hex without the sha256= prefix").isFalse();
		assertThat(verifier.verify(BODY, "sha256=nothexatall")).as("non-hex digest").isFalse();
		assertThat(verifier.verify(BODY, "sha256=abc")).as("odd-length hex").isFalse();
		assertThat(verifier.verify(BODY, "sha256=")).as("empty digest").isFalse();
	}

	@Test
	void rejectsANullBody() {
		assertThat(verifier.verify(null, EXPECTED)).isFalse();
	}

	/**
	 * A truncated-but-correct prefix must not pass. This is what constant-time comparison
	 * with a length check buys us over a naive {@code startsWith}-style match.
	 */
	@Test
	void rejectsATruncatedSignature() {
		assertThat(verifier.verify(BODY, EXPECTED.substring(0, EXPECTED.length() - 2))).isFalse();
	}

	private static final int PREFIX_LEN = "sha256=".length();
}
