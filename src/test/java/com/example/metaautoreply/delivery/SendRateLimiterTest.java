package com.example.metaautoreply.delivery;

import com.example.metaautoreply.config.AppProperties;
import com.example.metaautoreply.domain.enums.Platform;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SendRateLimiterTest {

	private SendRateLimiter limiterAllowing(int perHour) {
		return new SendRateLimiter(new AppProperties(2000, 1000, 5, perHour, 0));
	}

	@Test
	void allowsUpToTheConfiguredCeilingThenRefuses() {
		SendRateLimiter limiter = limiterAllowing(3);

		assertThat(limiter.tryConsume(Platform.IG)).isTrue();
		assertThat(limiter.tryConsume(Platform.IG)).isTrue();
		assertThat(limiter.tryConsume(Platform.IG)).isTrue();
		assertThat(limiter.tryConsume(Platform.IG)).as("ceiling reached").isFalse();
	}

	/** A burst on one network must not consume the other's allowance. */
	@Test
	void platformsHaveIndependentBuckets() {
		SendRateLimiter limiter = limiterAllowing(2);

		assertThat(limiter.tryConsume(Platform.IG)).isTrue();
		assertThat(limiter.tryConsume(Platform.IG)).isTrue();
		assertThat(limiter.tryConsume(Platform.IG)).isFalse();

		assertThat(limiter.tryConsume(Platform.FB)).as("Facebook is untouched").isTrue();
		assertThat(limiter.tryConsume(Platform.FB)).isTrue();
		assertThat(limiter.tryConsume(Platform.FB)).isFalse();
	}

	@Test
	void startsFullAndReportsRemainingTokens() {
		SendRateLimiter limiter = limiterAllowing(10);

		assertThat(limiter.availableTokens(Platform.IG)).isEqualTo(10);
		limiter.tryConsume(Platform.IG);
		assertThat(limiter.availableTokens(Platform.IG)).isEqualTo(9);
		assertThat(limiter.sendsPerHour()).isEqualTo(10);
	}

	/** Non-blocking is the contract: the dispatcher holds database row locks while it asks. */
	@Test
	void refusalIsImmediateNotBlocking() {
		SendRateLimiter limiter = limiterAllowing(1);
		limiter.tryConsume(Platform.IG);

		long startedAt = System.nanoTime();
		assertThat(limiter.tryConsume(Platform.IG)).isFalse();
		assertThat(System.nanoTime() - startedAt).isLessThan(100_000_000L);
	}
}
