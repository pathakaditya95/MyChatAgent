package com.example.metaautoreply.delivery;

import com.example.metaautoreply.delivery.exceptions.FatalMetaException;
import com.example.metaautoreply.delivery.exceptions.MetaApiException;
import com.example.metaautoreply.delivery.exceptions.ReauthMetaException;
import com.example.metaautoreply.delivery.exceptions.RetryableMetaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;

/**
 * Turns a Graph API error response into the right kind of exception.
 *
 * <p>Necessary because Meta answers HTTP 400 for conditions ranging from "you are going too
 * fast" to "this token is dead" to "that user cannot be messaged". Deciding on status alone
 * would either retry things that can never succeed, or abandon things that would.
 */
@Component
public class MetaErrorClassifier {

	private static final Logger log = LoggerFactory.getLogger(MetaErrorClassifier.class);

	/** Transient conditions and rate limits. */
	private static final Set<Integer> RETRYABLE = Set.of(1, 2, 4, 17, 32, 613);

	/** Token expired, revoked, or otherwise invalid. Needs a human. */
	private static final Set<Integer> REAUTH = Set.of(102, 190);

	/** Permanent rejections: bad parameter, permission, unavailable user. */
	private static final Set<Integer> FATAL = Set.of(10, 100, 200, 551);

	private final ObjectMapper mapper;

	public MetaErrorClassifier(ObjectMapper mapper) {
		this.mapper = mapper;
	}

	/**
	 * @param httpStatus the response status
	 * @param body       the raw response body, which may be empty or unparseable
	 * @return the exception to throw; never null
	 */
	public MetaApiException classify(int httpStatus, String body) {
		int code = -1;
		int subcode = -1;
		String message = "Graph API error";

		try {
			JsonNode error = mapper.readTree(body == null ? "{}" : body).path("error");
			code = error.path("code").asInt(-1);
			subcode = error.path("error_subcode").asInt(-1);
			message = error.path("message").asString(message);
		}
		catch (RuntimeException e) {
			log.warn("Graph API error body was not JSON (HTTP {}): {}", httpStatus, abbreviate(body));
		}

		String detail = "HTTP %d, code %d, subcode %d: %s".formatted(httpStatus, code, subcode, message);

		// A 5xx is transient regardless of what the body says.
		if (httpStatus >= 500) {
			return new RetryableMetaException(detail, httpStatus, code, subcode, body);
		}
		if (REAUTH.contains(code)) {
			log.error("Graph API rejected the access token ({}). It must be regenerated — every "
					+ "queued message will keep failing until it is.", detail);
			return new ReauthMetaException(detail, httpStatus, code, subcode, body);
		}
		if (RETRYABLE.contains(code)) {
			return new RetryableMetaException(detail, httpStatus, code, subcode, body);
		}
		if (FATAL.contains(code)) {
			// Full body on FATAL: these are the ones that need a human to read them.
			log.error("Graph API permanently rejected the request ({}). Body: {}", detail, abbreviate(body));
			return new FatalMetaException(detail, httpStatus, code, subcode, body);
		}

		// Unrecognised. Treated as fatal on purpose: retrying an error we do not understand
		// risks hammering Meta and tripping anti-spam, which is far more damaging than one
		// message failing. Record the code in NOTES.md and classify it explicitly.
		log.error("UNRECOGNISED Graph API error code — treating as fatal, and it should be added to "
				+ "MetaErrorClassifier and recorded in NOTES.md. {} Body: {}", detail, abbreviate(body));
		return new FatalMetaException(detail, httpStatus, code, subcode, body);
	}

	private static String abbreviate(String body) {
		if (body == null) {
			return "<empty>";
		}
		return body.length() <= 2000 ? body : body.substring(0, 2000) + "...<truncated>";
	}
}
