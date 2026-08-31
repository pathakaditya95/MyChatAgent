package com.example.metaautoreply.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the event processor and outbound dispatcher timers.
 *
 * <p>Conditional so tests can switch the timers off and drive a single batch by hand. Left on
 * by default; {@code application-test.yml} sets {@code autoreply.scheduling-enabled: false},
 * because a scheduler firing mid-assertion makes integration tests flaky in ways that are
 * miserable to debug.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "autoreply.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
