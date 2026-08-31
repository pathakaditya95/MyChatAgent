package com.example.metaautoreply.delivery;

import com.example.metaautoreply.config.MetaProperties;
import com.example.metaautoreply.domain.enums.Platform;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * The four Graph API calls this service makes.
 *
 * <p>Each returns the provider's message id, which is stored on the outbound row as proof of
 * delivery.
 *
 * <p>The access token travels in the {@code Authorization} header rather than as a query
 * parameter. Query strings end up in access logs, proxy logs and error reports; a
 * never-expiring System User token leaking into any of those is a serious incident.
 */
@Component
public class GraphApiClient {

	private static final Logger log = LoggerFactory.getLogger(GraphApiClient.class);

	private static final String RESILIENCE_NAME = "graphApi";

	private final RestClient restClient;
	private final MetaProperties props;
	private final MetaErrorClassifier errorClassifier;
	private final ObjectMapper mapper;

	public GraphApiClient(RestClient graphRestClient, MetaProperties props,
			MetaErrorClassifier errorClassifier, ObjectMapper mapper) {
		this.restClient = graphRestClient;
		this.props = props;
		this.errorClassifier = errorClassifier;
		this.mapper = mapper;
	}

	/** Public reply on an Instagram comment thread. */
	@Retry(name = RESILIENCE_NAME)
	@CircuitBreaker(name = RESILIENCE_NAME)
	public String replyToIgComment(String commentId, String text) {
		return post(path(commentId, "replies"), Map.of("message", text), "id",
				"IG public reply to comment " + commentId);
	}

	/** Public reply on a Facebook comment thread. */
	@Retry(name = RESILIENCE_NAME)
	@CircuitBreaker(name = RESILIENCE_NAME)
	public String replyToFbComment(String commentId, String text) {
		return post(path(commentId, "comments"), Map.of("message", text), "id",
				"FB public reply to comment " + commentId);
	}

	/**
	 * Private reply: a DM sent in response to a comment, addressed by comment id rather than by
	 * user. Limited by Meta to one per comment, which the database also enforces.
	 */
	@Retry(name = RESILIENCE_NAME)
	@CircuitBreaker(name = RESILIENCE_NAME)
	public String privateReply(Platform platform, String commentId, String text) {
		Map<String, Object> body = Map.of(
				"recipient", Map.of("comment_id", commentId),
				"message", Map.of("text", text));
		return post(path(messagingAccountId(), "messages"), body, "message_id",
				platform + " private reply to comment " + commentId);
	}

	/** A direct message addressed to a person, subject to the 24-hour messaging window. */
	@Retry(name = RESILIENCE_NAME)
	@CircuitBreaker(name = RESILIENCE_NAME)
	public String sendDm(Platform platform, String recipientId, String text) {
		Map<String, Object> body = Map.of(
				"recipient", Map.of("id", recipientId),
				"message", Map.of("text", text));
		return post(path(messagingAccountId(), "messages"), body, "message_id",
				platform + " DM to " + recipientId);
	}

	/**
	 * Sends one request, or pretends to.
	 *
	 * @param idField which field of the success body carries the id — {@code id} for comment
	 *                replies, {@code message_id} for the messaging endpoint
	 */
	private String post(String path, Map<String, Object> body, String idField, String description) {
		if (props.dryRun()) {
			// The whole point of dry-run is that nothing leaves the process. Logged in full so
			// the exact request can be inspected before going live.
			String synthetic = "dry-run-" + UUID.randomUUID();
			log.info("DRY RUN — would POST {}{} body={} ({}); returning synthetic id {}",
					props.graphBaseUrl(), path, mapper.writeValueAsString(body), description, synthetic);
			return synthetic;
		}

		String response = restClient.post()
				.uri(path)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + props.accessToken())
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.exchange((request, clientResponse) -> {
					String responseBody = new String(clientResponse.getBody().readAllBytes(),
							StandardCharsets.UTF_8);
					if (clientResponse.getStatusCode().isError()) {
						throw errorClassifier.classify(clientResponse.getStatusCode().value(), responseBody);
					}
					return responseBody;
				});

		String id = extractId(response, idField);
		log.info("Sent {} (provider id {})", description, id);
		return id;
	}

	private String extractId(String response, String idField) {
		JsonNode root = mapper.readTree(response == null ? "{}" : response);
		String id = root.path(idField).asString(null);
		if (id == null) {
			// Not fatal: the send succeeded. Losing the id only costs us traceability, so
			// record that rather than fail a message that Meta has already accepted.
			log.warn("Graph API success response had no '{}' field: {}", idField, response);
			return "unknown";
		}
		return id;
	}

	/**
	 * Both messaging calls go through the Facebook Page — including Instagram ones.
	 *
	 * <p>Instagram messaging via the Messenger Platform is addressed to the ID of the Page
	 * linked to the Instagram professional account, not to the Instagram user id:
	 * "send a POST request to the /&lt;PAGE_ID&gt;/messages endpoint". Sending to
	 * {@code /{ig-user-id}/messages} with a Page token fails with
	 * {@code (#3) Application does not have the capability to make this API call}, which reads
	 * like a permissions problem and is actually a wrong-endpoint problem.
	 *
	 * <p>{@code PLAN.md} Phase 7 specifies "{igUserId or pageId}", which is correct for the
	 * other Instagram flow — Instagram API with Instagram Login, using an IG user token. This
	 * service authenticates with a Page token, so the Page id applies to both platforms.
	 *
	 * <p>{@code META_IG_USER_ID} is still required: the normalizer compares it against event
	 * senders to recognise our own Instagram activity (compliance invariant #4).
	 */
	private String messagingAccountId() {
		return props.pageId();
	}

	private String path(String id, String edge) {
		return "/%s/%s/%s".formatted(props.graphVersion(), id, edge);
	}
}
