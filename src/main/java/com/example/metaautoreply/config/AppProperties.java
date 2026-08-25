package com.example.metaautoreply.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Pacing and retry knobs for the two schedulers, bound from the {@code autoreply.*} block.
 *
 * @param processIntervalMs  how often the event processor drains {@code inbound_event}
 * @param dispatchIntervalMs how often the dispatcher drains {@code outbound_message}
 * @param maxSendAttempts    attempts before a message is abandoned
 * @param sendsPerHour       per-platform send ceiling; see {@code SendRateLimiter}
 * @param jitterMs           upper bound on the random pause before each send
 */
@Validated
@ConfigurationProperties(prefix = "autoreply")
public record AppProperties(
		@Min(100) long processIntervalMs,
		@Min(100) long dispatchIntervalMs,
		@Min(1) int maxSendAttempts,
		@Min(1) int sendsPerHour,
		@Min(0) long jitterMs) {
}
