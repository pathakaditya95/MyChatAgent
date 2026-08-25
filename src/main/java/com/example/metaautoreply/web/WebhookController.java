package com.example.metaautoreply.web;

import com.example.metaautoreply.config.MetaProperties;
import com.example.metaautoreply.ingest.WebhookIngestService;
import com.example.metaautoreply.security.SignatureVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The endpoint Meta calls. Two operations: a one-off subscription handshake, and event intake.
 *
 * <p>Intake is kept deliberately thin — a signature check and one insert, no Graph API calls
 * and no rule matching — so it answers well inside Meta's timeout. Everything else happens on
 * the schedulers.
 */
@RestController
@RequestMapping("/webhook")
public class WebhookController {

	private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

	private final MetaProperties props;
	private final SignatureVerifier signatureVerifier;
	private final WebhookIngestService ingestService;

	public WebhookController(MetaProperties props, SignatureVerifier signatureVerifier,
			WebhookIngestService ingestService) {
		this.props = props;
		this.signatureVerifier = signatureVerifier;
		this.ingestService = ingestService;
	}

	/**
	 * Subscription verification. Meta calls this once when the callback URL is saved.
	 *
	 * <p>The challenge must come back as plain text. Returning it as JSON — quoted, or wrapped
	 * in an object — makes Meta refuse to activate the subscription.
	 */
	@GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
	public ResponseEntity<String> verify(
			@RequestParam("hub.mode") String mode,
			@RequestParam("hub.verify_token") String token,
			@RequestParam("hub.challenge") String challenge) {

		if ("subscribe".equals(mode) && props.verifyToken().equals(token)) {
			log.info("Webhook subscription verified");
			return ResponseEntity.ok(challenge);
		}
		log.warn("Rejecting webhook verification: mode='{}', token mismatch={}",
				mode, !props.verifyToken().equals(token));
		return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
	}

	/**
	 * Event intake.
	 *
	 * <p>The body is bound as {@code String} on purpose. Binding to a DTO would have Jackson
	 * deserialize and re-serialize it, changing the bytes, and the HMAC would never match.
	 *
	 * <p>Anything after a successful signature check returns 200 even if it blows up. A non-200
	 * makes Meta retry, and sustained failures make it disable the subscription outright — so a
	 * bug in our own parsing must not cost us the webhook. Failed rows are recoverable; a
	 * disabled subscription is not.
	 */
	@PostMapping
	public ResponseEntity<Void> receive(
			@RequestBody String rawBody,
			@RequestHeader(value = "X-Hub-Signature-256", required = false) String signature) {

		if (!signatureVerifier.verify(rawBody, signature)) {
			log.warn("Rejecting webhook delivery: invalid signature");
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}

		try {
			int inserted = ingestService.ingest(rawBody);
			log.debug("Accepted webhook delivery, {} new event(s)", inserted);
		}
		catch (Exception e) {
			log.error("Failed to ingest webhook delivery; acknowledging anyway to keep the "
					+ "subscription alive. Body length={}", rawBody.length(), e);
		}
		return ResponseEntity.ok().build();
	}
}
