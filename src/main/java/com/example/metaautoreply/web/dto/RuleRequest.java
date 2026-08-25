package com.example.metaautoreply.web.dto;

import com.example.metaautoreply.domain.enums.MatchType;
import com.example.metaautoreply.domain.enums.Platform;
import com.example.metaautoreply.domain.enums.TriggerType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Incoming rule definition.
 *
 * <p>The cross-field checks below are the ones that stop bad configuration reaching the
 * engine, where the consequences are silent rather than a 400.
 */
public record RuleRequest(

		@NotBlank(message = "name must not be blank")
		String name,

		@NotNull(message = "platform is required (IG or FB)")
		Platform platform,

		@NotNull(message = "triggerType is required (COMMENT or MESSAGE)")
		TriggerType triggerType,

		@NotNull(message = "matchType is required (EXACT, CONTAINS or REGEX)")
		MatchType matchType,

		@NotBlank(message = "keyword must not be blank")
		String keyword,

		String publicReply,

		String dmText,

		Boolean enabled,

		@Min(value = 0, message = "priority must not be negative")
		Integer priority) {

	/**
	 * A rule with neither reply does nothing at all — it would match, consume the event, and
	 * queue nothing, which looks identical to a broken rule from the outside.
	 */
	@AssertTrue(message = "at least one of publicReply or dmText must be provided")
	public boolean isAtLeastOneReplyPresent() {
		return notBlank(publicReply) || notBlank(dmText);
	}

	/**
	 * An uncompilable pattern would be rejected at match time, once per event, forever — and
	 * only visible in the logs. Far better to refuse it here.
	 */
	@AssertTrue(message = "keyword must be a valid regular expression when matchType is REGEX")
	public boolean isKeywordValidRegexWhenRequired() {
		if (matchType != MatchType.REGEX || keyword == null) {
			return true;
		}
		try {
			Pattern.compile(keyword);
			return true;
		}
		catch (PatternSyntaxException e) {
			return false;
		}
	}

	public boolean enabledOrDefault() {
		return enabled != null && enabled;
	}

	public int priorityOrDefault() {
		return priority != null ? priority : 100;
	}

	private static boolean notBlank(String value) {
		return value != null && !value.isBlank();
	}
}
