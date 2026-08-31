package com.example.metaautoreply.delivery;

import com.example.metaautoreply.config.AppProperties;
import com.example.metaautoreply.domain.enums.Platform;
import io.github.bucket4j.Bucket;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * Paces outbound sends, one independent bucket per platform.
 *
 * <p>Compliance invariant #6. Meta's documented ceiling for private replies to post and Reel
 * comments is 750/hour, but the published limit is not the real constraint — the anti-spam
 * heuristics are, and they are neither documented nor appealable. Staying well under, with
 * jitter between sends, keeps the Page and Instagram account clear of them. Exceeding them
 * risks the accounts, not just this service.
 *
 * <p>Buckets are per-platform so a burst of Instagram traffic cannot consume Facebook's
 * allowance. Refill is greedy: tokens trickle back continuously across the hour rather than
 * arriving all at once, which would allow a thundering herd on the hour boundary.
 */
@Component
public class SendRateLimiter {

	private final Map<Platform, Bucket> buckets = new EnumMap<>(Platform.class);
	private final int sendsPerHour;

	public SendRateLimiter(AppProperties props) {
		this.sendsPerHour = props.sendsPerHour();
		for (Platform platform : Platform.values()) {
			buckets.put(platform, newBucket(props.sendsPerHour()));
		}
	}

	private static Bucket newBucket(int perHour) {
		return Bucket.builder()
				.addLimit(limit -> limit.capacity(perHour).refillGreedy(perHour, Duration.ofHours(1)))
				.build();
	}

	/**
	 * Takes one token if any remain.
	 *
	 * @return false when the platform's allowance is exhausted. Non-blocking on purpose — the
	 *         dispatcher must defer the message and move on rather than sleep holding database
	 *         row locks.
	 */
	public boolean tryConsume(Platform platform) {
		return buckets.get(platform).tryConsume(1);
	}

	/** Remaining allowance, for logging and tests. */
	public long availableTokens(Platform platform) {
		return buckets.get(platform).getAvailableTokens();
	}

	public int sendsPerHour() {
		return sendsPerHour;
	}
}
