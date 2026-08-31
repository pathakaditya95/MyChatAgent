package com.example.metaautoreply.security;

import com.example.metaautoreply.config.MetaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Verifies the {@code X-Hub-Signature-256} header Meta sends with every webhook delivery.
 *
 * <p>The signature is an HMAC-SHA256 of the request body, keyed with the app secret, and it
 * must be computed over the <em>exact bytes Meta sent</em>. That is why the controller binds
 * the body as a {@code String} and hands it straight here: letting Jackson deserialize and
 * re-serialize changes key order and whitespace, and the signature then never matches.
 */
@Component
public class SignatureVerifier {

	private static final Logger log = LoggerFactory.getLogger(SignatureVerifier.class);

	private static final String PREFIX = "sha256=";
	private static final String ALGORITHM = "HmacSHA256";

	/**
	 * Every secret a delivery may legitimately be signed with, in try order.
	 *
	 * <p>An app that serves both networks through Instagram API with Instagram Login has two
	 * signing keys — Meta signs each delivery with the secret of the product it originated
	 * from. We cannot pick the right one up front: the platform is only knowable by parsing
	 * the body, and parsing an unverified body is exactly what we must not do. So a delivery
	 * is accepted if it matches <em>any</em> configured secret.
	 */
	private final List<byte[]> candidateSecrets;

	public SignatureVerifier(MetaProperties props) {
		String facebook = props.appSecret();
		String instagram = props.instagramAppSecret();

		List<byte[]> secrets = new ArrayList<>();
		secrets.add(facebook.getBytes(StandardCharsets.UTF_8));

		if (instagram != null && !instagram.isBlank()) {
			if (instagram.equals(facebook)) {
				// Counting this as a second key would make the rejection log claim two
				// secrets were tried when only one distinct value exists — which reads
				// like the configuration is complete when it is not.
				log.warn("META_INSTAGRAM_APP_SECRET is identical to META_APP_SECRET, so only one "
						+ "distinct signing key is configured. If deliveries from one network are "
						+ "being rejected, these two values are supposed to differ: the Facebook "
						+ "App Secret is under App Settings -> Basic, the Instagram App Secret "
						+ "under the Instagram product settings.");
			}
			else {
				secrets.add(instagram.getBytes(StandardCharsets.UTF_8));
			}
		}
		this.candidateSecrets = List.copyOf(secrets);
		log.info("Webhook signature verification configured with {} distinct secret(s)",
				candidateSecrets.size());
	}

	/**
	 * @param rawBody         the request body exactly as received
	 * @param signatureHeader the {@code X-Hub-Signature-256} value, formatted {@code sha256=<hex>}
	 * @return true only if the header is well-formed and matches
	 */
	public boolean verify(String rawBody, String signatureHeader) {
		if (rawBody == null || signatureHeader == null) {
			return false;
		}
		if (!signatureHeader.startsWith(PREFIX)) {
			log.warn("Rejecting webhook: signature header missing the '{}' prefix", PREFIX);
			return false;
		}

		byte[] provided;
		try {
			provided = HexFormat.of().parseHex(signatureHeader.substring(PREFIX.length()));
		}
		catch (IllegalArgumentException e) {
			log.warn("Rejecting webhook: signature header is not valid hex");
			return false;
		}

		// Constant-time comparison. String.equals short-circuits on the first differing
		// character, which leaks how much of a forged signature was correct.
		//
		// Every candidate is checked even after a match, so the work done does not depend
		// on which secret matched.
		boolean matched = false;
		for (byte[] secret : candidateSecrets) {
			matched |= MessageDigest.isEqual(hmac(rawBody, secret), provided);
		}
		if (!matched) {
			log.warn("Rejecting webhook: signature matched none of the {} configured secret(s). "
					+ "An app using Instagram API with Instagram Login has a separate Instagram "
					+ "App Secret — set META_INSTAGRAM_APP_SECRET if Instagram or Page deliveries "
					+ "are being refused.", candidateSecrets.size());
		}
		return matched;
	}

	/** How many <em>distinct</em> signing keys are configured. */
	public int configuredSecretCount() {
		return candidateSecrets.size();
	}

	/**
	 * Computes the signature for a body using the primary secret. Exposed so tests can
	 * generate valid headers without duplicating the algorithm.
	 */
	public String sign(String rawBody) {
		return sign(rawBody, candidateSecrets.get(0));
	}

	/** Signs with a specific secret, so tests can exercise each configured key. */
	public String sign(String rawBody, byte[] secret) {
		return PREFIX + HexFormat.of().formatHex(hmac(rawBody, secret));
	}

	private byte[] hmac(String rawBody, byte[] secret) {
		try {
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(new SecretKeySpec(secret, ALGORITHM));
			// UTF-8 round-trips the body faithfully: Meta sends UTF-8 JSON, and Spring's
			// String converter decodes with UTF-8 by default.
			return mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("HMAC-SHA256 unavailable", e);
		}
	}
}
