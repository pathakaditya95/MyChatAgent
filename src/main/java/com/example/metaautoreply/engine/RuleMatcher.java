package com.example.metaautoreply.engine;

import com.example.metaautoreply.domain.KeywordRule;
import com.example.metaautoreply.repo.KeywordRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Finds the first enabled rule matching an event, lowest {@code priority} value first.
 *
 * <p>All matching is case-insensitive.
 */
@Component
public class RuleMatcher {

	private static final Logger log = LoggerFactory.getLogger(RuleMatcher.class);

	/**
	 * Ceiling on character reads during one regex match. A pattern like {@code (a+)+$} against
	 * a long string backtracks exponentially; without a bound, one bad rule stalls the whole
	 * scheduler. Generous enough that no sane pattern on a comment-length string reaches it.
	 */
	private static final int MATCH_STEP_BUDGET = 200_000;

	/** Rules are few and long-lived, so an unbounded cache would still be small — but bound it. */
	private static final int MAX_CACHED_PATTERNS = 500;

	private final KeywordRuleRepository rules;
	private final Map<String, Pattern> patternCache = new ConcurrentHashMap<>();

	public RuleMatcher(KeywordRuleRepository rules) {
		this.rules = rules;
	}

	public Optional<KeywordRule> match(NormalizedEvent event) {
		List<KeywordRule> candidates = rules
				.findByEnabledTrueAndPlatformAndTriggerTypeOrderByPriorityAsc(
						event.platform(), event.triggerType());

		for (KeywordRule rule : candidates) {
			if (matches(rule, event.text())) {
				return Optional.of(rule);
			}
		}
		return Optional.empty();
	}

	private boolean matches(KeywordRule rule, String text) {
		String keyword = rule.getKeyword();
		if (keyword == null || keyword.isBlank() || text == null) {
			return false;
		}

		return switch (rule.getMatchType()) {
			case EXACT -> text.trim().equalsIgnoreCase(keyword.trim());
			// Locale.ROOT, not the default locale: in Turkish, "I".toLowerCase() is "ı", so a
			// rule keyed on "INFO" would stop matching "info" for users in a tr locale.
			case CONTAINS -> text.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
			case REGEX -> matchesRegex(rule, text);
		};
	}

	private boolean matchesRegex(KeywordRule rule, String text) {
		Pattern pattern;
		try {
			pattern = compiled(rule.getKeyword());
		}
		catch (PatternSyntaxException e) {
			// Phase 9 validates patterns on write, but a rule inserted straight into the
			// database bypasses that. Skip it rather than break the batch.
			log.error("Rule {} ('{}') has an invalid regex and will never match: {}",
					rule.getId(), rule.getName(), e.getMessage());
			return false;
		}

		try {
			return pattern.matcher(new BoundedCharSequence(text, MATCH_STEP_BUDGET)).find();
		}
		catch (MatchBudgetExceededException e) {
			log.error("Rule {} ('{}') exceeded the regex step budget and was skipped. The pattern "
					+ "is likely catastrophically backtracking — review it.", rule.getId(), rule.getName());
			return false;
		}
	}

	private Pattern compiled(String regex) {
		Pattern cached = patternCache.get(regex);
		if (cached != null) {
			return cached;
		}
		Pattern compiled = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
		if (patternCache.size() >= MAX_CACHED_PATTERNS) {
			patternCache.clear();
		}
		patternCache.put(regex, compiled);
		return compiled;
	}

	/** Visible for tests: a rule edited in place must not keep matching by its old pattern. */
	public void clearPatternCache() {
		patternCache.clear();
	}

	/**
	 * Aborts a regex match once it has read more characters than the budget allows.
	 *
	 * <p>This is how the timeout is enforced without a watchdog thread: the regex engine reads
	 * the subject through {@code charAt}, so counting those reads bounds the work it can do.
	 */
	private static final class BoundedCharSequence implements CharSequence {

		private final CharSequence delegate;
		private final int budget;
		private int reads;

		private BoundedCharSequence(CharSequence delegate, int budget) {
			this.delegate = delegate;
			this.budget = budget;
		}

		@Override
		public char charAt(int index) {
			if (++reads > budget) {
				throw new MatchBudgetExceededException();
			}
			return delegate.charAt(index);
		}

		@Override
		public int length() {
			return delegate.length();
		}

		@Override
		public CharSequence subSequence(int start, int end) {
			// Must stay bounded, and must share the counter, or a sub-match escapes the budget.
			BoundedCharSequence sub = new BoundedCharSequence(delegate.subSequence(start, end), budget);
			sub.reads = this.reads;
			return sub;
		}

		@Override
		public String toString() {
			return delegate.toString();
		}
	}

	/** Unchecked so it can escape {@code charAt}, which cannot declare a checked exception. */
	private static final class MatchBudgetExceededException extends RuntimeException {
		private MatchBudgetExceededException() {
			super(null, null, false, false);
		}
	}
}
