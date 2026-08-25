package com.example.metaautoreply.delivery;

import com.example.metaautoreply.config.AppProperties;
import com.example.metaautoreply.delivery.exceptions.FatalMetaException;
import com.example.metaautoreply.delivery.exceptions.ReauthMetaException;
import com.example.metaautoreply.delivery.exceptions.RetryableMetaException;
import com.example.metaautoreply.domain.OutboundMessage;
import com.example.metaautoreply.domain.enums.OutboundStatus;
import com.example.metaautoreply.domain.enums.Platform;
import com.example.metaautoreply.repo.OutboundMessageRepository;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Drains {@code outbound_message}: sends what is due, paces it, and decides what to do with
 * whatever comes back.
 */
@Component
public class OutboundDispatcher {

	private static final Logger log = LoggerFactory.getLogger(OutboundDispatcher.class);

	private static final int BATCH_SIZE = 10;

	/** How far to defer when the rate limiter or the circuit breaker says no. */
	private static final Duration DEFERRAL = Duration.ofSeconds(60);

	private final OutboundMessageRepository outbound;
	private final GraphApiClient graphApi;
	private final SendRateLimiter rateLimiter;
	private final AppProperties props;

	public OutboundDispatcher(OutboundMessageRepository outbound, GraphApiClient graphApi,
			SendRateLimiter rateLimiter, AppProperties props) {
		this.outbound = outbound;
		this.graphApi = graphApi;
		this.rateLimiter = rateLimiter;
		this.props = props;
	}

	/**
	 * Sends one batch.
	 *
	 * <p>{@code @Transactional} is load-bearing: {@code lockBatch} takes {@code FOR UPDATE SKIP
	 * LOCKED} row locks that vanish the instant the transaction ends. Without it, two
	 * dispatchers would send the same message twice.
	 */
	@Scheduled(fixedDelayString = "${autoreply.dispatch-interval-ms}")
	@Transactional
	public void dispatchBatch() {
		List<OutboundMessage> batch = outbound.lockBatch(BATCH_SIZE);
		if (batch.isEmpty()) {
			return;
		}
		log.debug("Dispatching {} message(s)", batch.size());

		for (OutboundMessage message : batch) {
			// Compliance invariant #6. Stop the whole batch rather than skipping ahead: the
			// remaining rows are for the same platforms and would fail the same check, and
			// continuing would spin through the batch burning database work for nothing.
			if (!rateLimiter.tryConsume(message.getPlatform())) {
				log.info("Rate limit reached for {} ({}/hour); deferring the rest of this batch",
						message.getPlatform(), rateLimiter.sendsPerHour());
				defer(message);
				break;
			}
			if (!send(message)) {
				break;
			}
		}
	}

	/**
	 * @return false when the batch should stop early
	 */
	private boolean send(OutboundMessage message) {
		try {
			pauseForJitter();
			String providerId = dispatch(message);

			message.setStatus(OutboundStatus.SENT);
			message.setSentAt(Instant.now());
			message.setProviderMsgId(providerId);
			message.setLastError(null);
			outbound.save(message);
			log.info("Sent {} {} to {} (provider id {})", message.getPlatform(), message.getKind(),
					message.getTargetId(), providerId);
			return true;
		}
		catch (CallNotPermittedException e) {
			// The circuit breaker is open: Graph API is unhealthy, and this message has done
			// nothing wrong. Deliberately does NOT count an attempt — otherwise a 30-second
			// outage plus a one-second dispatch interval would burn through max-send-attempts
			// and abandon healthy messages within half a minute.
			log.warn("Circuit breaker open; deferring {} and the rest of this batch", message.getId());
			defer(message);
			return false;
		}
		catch (ReauthMetaException e) {
			message.setStatus(OutboundStatus.FAILED);
			message.setLastError(truncate(e.getMessage()));
			outbound.save(message);
			log.error("ACCESS TOKEN NEEDS REGENERATION — message {} failed and every other queued "
					+ "message will fail the same way until META_ACCESS_TOKEN is replaced. {}",
					message.getId(), e.getMessage());
			return true;
		}
		catch (FatalMetaException e) {
			message.setStatus(OutboundStatus.FAILED);
			message.setLastError(truncate(e.getMessage()));
			outbound.save(message);
			log.error("Message {} permanently rejected, not retrying: {}", message.getId(), e.getMessage());
			return true;
		}
		catch (RetryableMetaException e) {
			backOff(message, e.getMessage());
			return true;
		}
		catch (Exception e) {
			// Unexpected — a socket reset, a serialization problem. Treated as transient so a
			// blip does not permanently fail a message; max-send-attempts still bounds it.
			backOff(message, e.toString());
			return true;
		}
	}

	private String dispatch(OutboundMessage message) {
		Platform platform = message.getPlatform();
		String target = message.getTargetId();
		String body = message.getBody();

		return switch (message.getKind()) {
			case PUBLIC_REPLY -> platform == Platform.IG
					? graphApi.replyToIgComment(target, body)
					: graphApi.replyToFbComment(target, body);
			case PRIVATE_REPLY -> graphApi.privateReply(platform, target, body);
			case DM -> graphApi.sendDm(platform, target, body);
		};
	}

	/** Exponential backoff: 2, 4, 8... minutes, then give up at the attempt ceiling. */
	private void backOff(OutboundMessage message, String error) {
		int attempts = message.getAttempts() + 1;
		message.setAttempts(attempts);
		message.setLastError(truncate(error));

		if (attempts >= props.maxSendAttempts()) {
			message.setStatus(OutboundStatus.ABANDONED);
			log.error("Message {} abandoned after {} attempts: {}", message.getId(), attempts, error);
		}
		else {
			Duration wait = Duration.ofMinutes(1L << attempts);
			message.setNextAttemptAt(Instant.now().plus(wait));
			log.warn("Message {} failed (attempt {}/{}), retrying in {} minutes: {}",
					message.getId(), attempts, props.maxSendAttempts(), wait.toMinutes(), error);
		}
		outbound.save(message);
	}

	/** Push a message out without counting it as a failed attempt. */
	private void defer(OutboundMessage message) {
		message.setNextAttemptAt(Instant.now().plus(DEFERRAL));
		outbound.save(message);
	}

	/**
	 * A short random pause between sends.
	 *
	 * <p>Bursts of perfectly evenly spaced requests look automated, which is exactly what
	 * Meta's anti-spam heuristics watch for. Blocking here is cheap on virtual threads.
	 */
	private void pauseForJitter() {
		long jitter = props.jitterMs();
		if (jitter <= 0) {
			return;
		}
		try {
			Thread.sleep(ThreadLocalRandom.current().nextLong(jitter + 1));
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static String truncate(String message) {
		if (message == null) {
			return null;
		}
		return message.length() <= 1000 ? message : message.substring(0, 1000);
	}
}
