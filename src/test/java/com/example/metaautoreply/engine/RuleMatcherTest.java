package com.example.metaautoreply.engine;

import com.example.metaautoreply.domain.KeywordRule;
import com.example.metaautoreply.domain.enums.MatchType;
import com.example.metaautoreply.domain.enums.Platform;
import com.example.metaautoreply.domain.enums.TriggerType;
import com.example.metaautoreply.repo.KeywordRuleRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class RuleMatcherTest {

	private final KeywordRuleRepository repository = mock(KeywordRuleRepository.class);
	private final RuleMatcher matcher = new RuleMatcher(repository);

	private void givenRules(KeywordRule... rules) {
		given(repository.findByEnabledTrueAndPlatformAndTriggerTypeOrderByPriorityAsc(any(), any()))
				.willReturn(List.of(rules));
	}

	private static KeywordRule rule(MatchType type, String keyword, int priority) {
		KeywordRule rule = new KeywordRule("rule-" + keyword, Platform.IG, TriggerType.COMMENT,
				type, keyword, "public", "dm", true, priority);
		rule.setId((long) priority);
		return rule;
	}

	private static NormalizedEvent event(String text) {
		return new NormalizedEvent(Platform.IG, TriggerType.COMMENT, "c1", "u1", "user",
				text, "m1", false, null);
	}

	// --- EXACT ------------------------------------------------------------------------

	@Test
	void exactMatchesWholeStringIgnoringCaseAndSurroundingSpace() {
		givenRules(rule(MatchType.EXACT, "info", 10));

		assertThat(matcher.match(event("info"))).isPresent();
		assertThat(matcher.match(event("INFO"))).isPresent();
		assertThat(matcher.match(event("  Info  "))).isPresent();
	}

	@Test
	void exactDoesNotMatchASubstring() {
		givenRules(rule(MatchType.EXACT, "info", 10));

		assertThat(matcher.match(event("more info please"))).isEmpty();
		assertThat(matcher.match(event("information"))).isEmpty();
	}

	// --- CONTAINS ---------------------------------------------------------------------

	@Test
	void containsMatchesAnywhereIgnoringCase() {
		givenRules(rule(MatchType.CONTAINS, "price", 10));

		assertThat(matcher.match(event("what is the price?"))).isPresent();
		assertThat(matcher.match(event("PRICE"))).isPresent();
		assertThat(matcher.match(event("PriceList"))).isPresent();
		assertThat(matcher.match(event("how much?"))).isEmpty();
	}

	// --- REGEX ------------------------------------------------------------------------

	@Test
	void regexMatchesCaseInsensitively() {
		givenRules(rule(MatchType.REGEX, "^price\\b.*\\?$", 10));

		assertThat(matcher.match(event("Price of this?"))).isPresent();
		assertThat(matcher.match(event("PRICE now?"))).isPresent();
		assertThat(matcher.match(event("the price?"))).as("anchored at start").isEmpty();
	}

	@Test
	void anInvalidRegexIsSkippedRatherThanThrowing() {
		givenRules(rule(MatchType.REGEX, "([unclosed", 10));

		assertThatCode(() -> assertThat(matcher.match(event("anything"))).isEmpty())
				.doesNotThrowAnyException();
	}

	/**
	 * A user-editable rule is an attack surface on our own scheduler: {@code (a+)+$} against a
	 * non-matching string backtracks exponentially and would hang the batch forever. The step
	 * budget must abort it quickly.
	 */
	@Test
	void aCatastrophicallyBacktrackingRegexIsAbortedQuickly() {
		givenRules(rule(MatchType.REGEX, "(a+)+$", 10));
		String evil = "a".repeat(40) + "!";

		long startedAt = System.nanoTime();
		Optional<KeywordRule> result = matcher.match(event(evil));
		Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

		assertThat(result).isEmpty();
		assertThat(elapsed).as("must abort, not hang").isLessThan(Duration.ofSeconds(5));
	}

	// --- Ordering and caching ---------------------------------------------------------

	@Test
	void firstMatchInPriorityOrderWins() {
		// The repository returns them ordered; the matcher must not reorder or keep searching.
		givenRules(rule(MatchType.CONTAINS, "price", 10), rule(MatchType.CONTAINS, "pri", 20));

		assertThat(matcher.match(event("price?")).orElseThrow().getKeyword()).isEqualTo("price");
	}

	@Test
	void noRulesMeansNoMatch() {
		givenRules();
		assertThat(matcher.match(event("anything"))).isEmpty();
	}

	@Test
	void patternsAreReusedAcrossCallsAndCanBeCleared() {
		givenRules(rule(MatchType.REGEX, "hello", 10));

		assertThat(matcher.match(event("hello"))).isPresent();
		assertThat(matcher.match(event("hello again"))).isPresent();

		matcher.clearPatternCache();
		assertThat(matcher.match(event("hello"))).as("still works after a cache clear").isPresent();
	}

	@Test
	void aBlankKeywordNeverMatches() {
		givenRules(rule(MatchType.CONTAINS, "   ", 10));
		assertThat(matcher.match(event("anything"))).isEmpty();
	}
}
